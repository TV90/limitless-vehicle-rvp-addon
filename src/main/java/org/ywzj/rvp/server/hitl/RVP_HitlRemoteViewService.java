package org.ywzj.rvp.server.hitl;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_HitlRemoteConfig;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.hitl.RVP_HitlChunkMapAccess;
import org.ywzj.rvp.hitl.RVP_HitlRemoteWindow;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.hitl.S2CHitlRemoteChunk;
import org.ywzj.rvp.network.hitl.S2CHitlRemoteView;
import org.ywzj.rvp.weapon.data.RVP_HitlRemoteViewData;
import java.util.*;

/** 显式开启的 HITL 会话：有界区块票据、差量发送、真实实体跟踪和完整退出清理。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HitlRemoteViewService {
    /** 实体 ticking 中心票据；40 tick 自然过期仅作异常兜底，正常移动会显式释放。 */
    private static final TicketType<Long> TICKET = TicketType.create("rvp_hitl_view", Long::compare, 40);
    /** 距离参数 2 对应中心实体 ticking；实际地形加载还包括 Minecraft 依赖邻域。 */
    private static final int TICKET_DISTANCE = 2;
    /** 五秒没有客户端观察心跳就撤销授权，不让失联客户端留下区块加载器。 */
    private static final int HEARTBEAT_TIMEOUT = 100;
    /** 以服务器实例隔离，集成服重进世界不会继承旧会话。 */
    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();

    private RVP_HitlRemoteViewService() {}

    /** 发射后的第一个实体 tick 建立会话；调用方只尝试一次，不会在用户退出后自动重开。 */
    public static boolean start(RVP_MissileEntity missile, RVP_HitlRemoteViewData config, double range) {
        if (!config.isEnabled() || !(missile.level() instanceof ServerLevel level)
                || !(missile.getOwner() instanceof ServerPlayer player)) return false;
        State state = STATES.computeIfAbsent(level.getServer(), ignored -> new State());
        Session previous = state.sessions.remove(player.getUUID());
        if (previous != null) finish(previous, "replaced", true);
        // 远程窗口由专用服务器上限约束，允许相机看得比母车普通视距远。
        int radius = Math.min(config.getRadiusChunks(), RVP_HitlRemoteConfig.radius());
        Session session = new Session(player, missile, config, radius,
                Math.min(Math.max(1, range), RVP_HitlRemoteConfig.range()));
        if (state.sessions.size() >= RVP_HitlRemoteConfig.sessions() || !valid(session)) {
            finish(session, "admission-rejected", true);
            return false;
        }
        state.sessions.put(player.getUUID(), session);
        sendWindow(session, true);
        debug(session, "start", 0, 0);
        return true;
    }

    /** 心跳来自当前连接并匹配不可复用的会话 UUID；旧弹的退出包不会关闭新弹。 */
    public static void heartbeat(ServerPlayer player, UUID sessionId, boolean stop) {
        if (player == null) return;
        State state = STATES.get(player.server);
        Session session = state == null ? null : state.sessions.get(player.getUUID());
        if (session == null || !session.id.equals(sessionId) || session.player != player) return;
        if (stop || !valid(session)) {
            state.sessions.remove(player.getUUID());
            finish(session, stop ? "client-exit" : "invalid-heartbeat", true);
        } else {
            session.lastHeartbeat = session.level.getGameTime();
        }
    }

    /** 服务端输入入口再次校验当前导弹会话，拒绝退出/离车/超距后的滞后操作。 */
    public static boolean isControlling(RVP_MissileEntity missile) {
        if (!(missile.getOwner() instanceof ServerPlayer player)) return false;
        State state = STATES.get(player.server);
        Session session = state == null ? null : state.sessions.get(player.getUUID());
        return session != null && session.missile == missile && valid(session);
    }

    /** 原版 updatePlayer 的最小入口：仅额外授权的真实实体使用原版配对集合。 */
    public static boolean keepPaired(ServerPlayer player, Entity entity, ServerEntity tracker,
                                     Set<ServerPlayerConnection> seenBy) {
        State state = STATES.get(player.server);
        Session session = state == null ? null : state.sessions.get(player.getUUID());
        if (session == null || entity == player || entity.level() != session.level
                || !session.entities.contains(entity.getId()) || !entity.broadcastToPlayer(player)) return false;
        if (seenBy.add(player.connection)) tracker.addPairing(player);
        return true;
    }

    /** 给原版方块、方块实体、光照差量广播追加已收到该区块的远程观察者。 */
    public static List<ServerPlayer> appendObservers(ServerLevel level, ChunkPos pos, List<ServerPlayer> original) {
        State state = STATES.get(level.getServer());
        if (state == null || state.sessions.isEmpty()) return original;
        List<ServerPlayer> result = null;
        for (Session session : state.sessions.values()) {
            if (session.level == level && session.sent.contains(pos.toLong()) && !original.contains(session.player)) {
                if (result == null) result = new ArrayList<>(original);
                result.add(session.player);
            }
        }
        return result == null ? original : result;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        State state = STATES.get(server);
        if (state == null) return;
        for (Iterator<Session> iterator = state.sessions.values().iterator(); iterator.hasNext();) {
            Session session = iterator.next();
            if (!valid(session) || session.level.getGameTime() - session.lastHeartbeat > HEARTBEAT_TIMEOUT) {
                iterator.remove();
                finish(session, "ended", true);
            }
        }
        List<Session> sessions = new ArrayList<>(state.sessions.values());
        if (!sessions.isEmpty()) Collections.rotate(sessions, -(state.rotation++ % sessions.size()));
        Set<LeaseKey> desired = new HashSet<>();
        for (Session session : sessions) {
            ChunkPos center = session.missile.chunkPosition();
            RVP_HitlRemoteWindow next = new RVP_HitlRemoteWindow(center.x, center.z, session.radius);
            if (!next.equals(session.window)) {
                session.window = next;
                session.sent.removeIf(key -> !next.contains(key));
                sendWindow(session, true);
            }
            var velocity = session.missile.getDeltaMovement();
            session.leases = next.leases(velocity.x, velocity.z, session.config.getPrefetchChunks());
            for (long key : session.leases) desired.add(new LeaseKey(session.level, key));
        }
        // 先撤销离开所有窗口的票据；不同会话重叠时只持有一张，不提前释放共享区块。
        for (Iterator<Map.Entry<LeaseKey, Long>> iterator = state.tickets.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            LeaseKey key = entry.getKey();
            if (!desired.contains(key)) {
                release(key);
                iterator.remove();
            } else if (key.level.getGameTime() - entry.getValue() >= 20) {
                issue(key);
                entry.setValue(key.level.getGameTime());
            }
        }
        int pending = 0;
        for (LeaseKey key : state.tickets.keySet()) if (!ready(key)) pending++;
        int loadBudget = RVP_HitlRemoteConfig.load();
        int sendBudget = RVP_HitlRemoteConfig.send();
        for (Session session : sessions) {
            int loaded = 0;
            for (long position : session.leases) {
                if (loaded >= session.config.getLoadChunksPerTick() || loadBudget <= 0) break;
                LeaseKey key = new LeaseKey(session.level, position);
                if (state.tickets.containsKey(key)) continue;
                boolean wasReady = ready(key);
                if (!wasReady && pending >= RVP_HitlRemoteConfig.pending()) continue;
                issue(key);
                state.tickets.put(key, session.level.getGameTime());
                if (!wasReady) pending++;
                loadBudget--;
                loaded++;
            }
            int sent = 0;
            for (long key : session.window.chunks()) {
                if (sent >= session.config.getSendChunksPerTick() || sendBudget <= 0) break;
                if (session.sent.contains(key)) continue;
                var chunk = session.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk == null) continue;
                // 原版区块包携带完整地形/方块实体/光照；先发地形，再让实体进入同一连接。
                var packet = new ClientboundLevelChunkWithLightPacket(chunk, session.level.getLightEngine(), null, null);
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> session.player),
                        new S2CHitlRemoteChunk(session.id, packet));
                session.sent.add(key);
                sent++;
                sendBudget--;
            }
            // 窗口或首发地形变化即时重算；静止观察每 5 tick 检索空间索引，避免扫描整维度。
            if (sent > 0 || server.getTickCount() % 5 == 0 || session.entities.isEmpty()) refreshEntities(session);
            if (server.getTickCount() % 100 == 0) debug(session, "tick", loaded, sent);
        }
        if (state.sessions.isEmpty() && state.tickets.isEmpty()) STATES.remove(server);
    }

    /** 操作者不能借观察会话离车、跨维度或操纵其他人的导弹。 */
    private static boolean valid(Session session) {
        ServerPlayer player = session.player;
        RVP_MissileEntity missile = session.missile;
        return player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && player.isAlive() && !player.isSpectator() && missile.isAlive()
                && player.level() == session.level && missile.level() == session.level
                // 远处新区块未就绪时，原版可访问实体索引会暂时隐藏弹体；不能据此终止加载会话。
                // Session 只由发射实体建立并持有该实例，isAlive/维度/所有者负责防止旧实例继续授权。
                && missile.getOwner() == player
                && missile.rvp$isHitlActive() && missile.rvp$isRemoteHitlConfigured()
                && missile.getShooterVehicle() != null && missile.getShooterVehicle().isAlive()
                && player.getVehicle() == missile.getShooterVehicle()
                && Double.isFinite(player.distanceToSqr(missile))
                && player.distanceToSqr(missile) <= session.range * session.range;
    }

    private static void refreshEntities(Session session) {
        double minX = session.window.x() * 16.0 - session.radius * 16.0;
        double minZ = session.window.z() * 16.0 - session.radius * 16.0;
        double side = (session.radius * 2 + 1) * 16.0;
        AABB bounds = new AABB(minX, Math.min(session.level.getMinBuildHeight(), session.missile.getY() - 64), minZ,
                minX + side, Math.max(session.level.getMaxBuildHeight(), session.missile.getY() + 64), minZ + side);
        List<Entity> nearby = session.level.getEntities((Entity) null, bounds,
                entity -> entity.isAlive() && entity != session.player
                        && session.sent.contains(entity.chunkPosition().toLong()) && entity.broadcastToPlayer(session.player));
        nearby.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(session.missile)));
        Set<Integer> next = new LinkedHashSet<>();
        next.add(session.missile.getId());
        for (Entity entity : nearby) {
            if (next.size() >= RVP_HitlRemoteConfig.entities()) break;
            next.add(entity.getId());
        }
        Set<Integer> affected = new HashSet<>(session.entities);
        affected.addAll(next);
        session.entities = next;
        RVP_HitlChunkMapAccess access = (RVP_HitlChunkMapAccess) session.level.getChunkSource().chunkMap;
        // 同一原版配对集合负责近远切换，避免额外代理与真实实体重影。
        for (int id : affected) access.rvp$updateObserver(id, session.player);
    }

    private static boolean ready(LeaseKey key) {
        int x = ChunkPos.getX(key.chunk), z = ChunkPos.getZ(key.chunk);
        return key.level.getChunkSource().getChunkNow(x, z) != null
                && key.level.isPositionEntityTicking(new BlockPos(x * 16, key.level.getMinBuildHeight(), z * 16));
    }

    private static void issue(LeaseKey key) {
        key.level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(key.chunk), TICKET_DISTANCE, key.chunk);
    }

    private static void release(LeaseKey key) {
        key.level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(key.chunk), TICKET_DISTANCE, key.chunk);
    }

    private static void sendWindow(Session session, boolean active) {
        // 原版有效渲染距离受服务器通告限制；只放宽本观察者的客户端网格，
        // 服务端身体订阅范围不变。结束时用服务器当前普通视距恢复，避免残留。
        int ordinaryRadius = session.level.getServer().getPlayerList().getViewDistance();
        session.player.connection.send(new ClientboundSetChunkCacheRadiusPacket(
                active ? Math.max(ordinaryRadius, session.radius) : ordinaryRadius));
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> session.player),
                new S2CHitlRemoteView(session.id, session.missile.getUUID(), session.missile.getId(),
                        session.level.dimension().location(), active, session.window.x(), session.window.z(), session.radius));
    }

    /** 必须先从授权表移除，再调用原版 updatePlayer，让正常玩家距离规则接管。 */
    private static void finish(Session session, String reason, boolean notify) {
        session.missile.rvp$exitHitl();
        RVP_HitlChunkMapAccess access = (RVP_HitlChunkMapAccess) session.level.getChunkSource().chunkMap;
        for (int id : session.entities) access.rvp$updateObserver(id, session.player);
        if (notify && session.player.server.getPlayerList().getPlayer(session.player.getUUID()) == session.player) {
            sendWindow(session, false);
        }
        debug(session, reason, 0, 0);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        State state = STATES.get(player.server);
        Session session = state == null ? null : state.sessions.remove(player.getUUID());
        if (session != null) finish(session, "logout", false);
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        // 世界已结束，票据随世界销毁；清除全部引用，避免单机重进继承状态。
        STATES.remove(event.getServer());
    }

    private static void debug(Session session, String action, int load, int send) {
        if (RVP_DebugFlags.HITL.isEnabled()) LogUtils.getLogger().info(
                "[RVP-HITL-STREAM] action={} session={} missile={} center={},{} radius={} sent={} entities={} newTickets={} packets={}",
                action, session.id, session.missile.getId(), session.window.x(), session.window.z(), session.radius,
                session.sent.size(), session.entities.size(), load, send);
    }

    /** 同维度、同区块的所有会话共享一张票据。 */
    private record LeaseKey(ServerLevel level, long chunk) {}

    private static final class State {
        /** 插入有序会话表，每 tick 轮换预算起点。 */
        final Map<UUID, Session> sessions = new LinkedHashMap<>();
        /** 当前票据及上次续租时间。 */
        final Map<LeaseKey, Long> tickets = new HashMap<>();
        /** 公平调度游标。 */
        int rotation;
    }

    private static final class Session {
        /** 会话身份、连接和实际导弹均不可替换。 */
        final UUID id = UUID.randomUUID();
        final ServerPlayer player;
        final RVP_MissileEntity missile;
        final ServerLevel level;
        final RVP_HitlRemoteViewData config;
        /** 经服务器上限约束的实际窗口和控制距离。 */
        final int radius;
        final double range;
        /** 最后一次收到观察心跳的服务端 gameTime。 */
        long lastHeartbeat;
        /** 当前相机窗口及本 tick 的预取请求。 */
        RVP_HitlRemoteWindow window;
        List<Long> leases = List.of();
        /** 本会话实际发送过且仍在窗口内的区块。 */
        final Set<Long> sent = new HashSet<>();
        /** 经数量限制的真实实体授权集合。 */
        Set<Integer> entities = Set.of();

        Session(ServerPlayer player, RVP_MissileEntity missile, RVP_HitlRemoteViewData config, int radius, double range) {
            this.player = player;
            this.missile = missile;
            this.level = (ServerLevel) missile.level();
            this.config = config;
            this.radius = radius;
            this.range = range;
            this.lastHeartbeat = level.getGameTime();
            this.window = new RVP_HitlRemoteWindow(missile.chunkPosition().x, missile.chunkPosition().z, radius);
        }
    }
}
