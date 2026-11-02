package org.ywzj.rvp.client.hitl;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.hitl.RVP_HitlRemoteWindow;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.hitl.C2SHitlRemoteHeartbeat;
import org.ywzj.rvp.network.hitl.S2CHitlRemoteChunk;
import org.ywzj.rvp.network.hitl.S2CHitlRemoteView;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 导弹专用的有界附加区块缓存；原版缓存继续以玩家/母车为中心。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientHitlRemoteView {
    /** 区块编译线程也会查询缓存；会话发布和区块表分别使用 volatile 和并发容器。 */
    private static volatile Session current;
    /** 读取原版槽位时跳过附加缓存，防止查询递归和退出时误认旧副本。 */
    private static final ThreadLocal<Boolean> NATIVE_LOOKUP = ThreadLocal.withInitial(() -> false);
    /** 上次摆放的地形视区，用于只在跨区块或重建后移动整个渲染网格。 */
    private static ViewArea lastArea;
    private static int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
    /** 退出远程模式时恢复一次原版视区。 */
    private static boolean terrainWasRemote;

    private RVP_ClientHitlRemoteView() {}

    /** 服务端开始/移动/结束窗口；同一个导弹会话不会反复重置相机操控状态。 */
    public static void accept(S2CHitlRemoteView message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(message.dimension())) return;
        Session session = current;
        if (!message.active()) {
            if (session != null && session.id.equals(message.session())) clear(false);
            // 服务端真结束必须即时释放窗口；失图显示由独立计时接管，不继续强载。
            RVP_ClientHitlState.endFeed(message.missileId());
            return;
        }
        if (session == null || !session.id.equals(message.session())) {
            clear(false);
            session = new Session(message, mc.level);
            current = session;
        }
        session.window = new RVP_HitlRemoteWindow(message.x(), message.z(), message.radius());
        for (long key : new ArrayList<>(session.chunks.keySet())) {
            if (!session.window.contains(key)) release(session, key);
        }
    }

    /** 只接收当前会话、当前窗口的地图包；保持原版槽位和附加缓存引用同一个已存在区块。 */
    public static void acceptChunk(S2CHitlRemoteChunk message) {
        Session session = current;
        Minecraft mc = Minecraft.getInstance();
        if (session == null || session.level != mc.level || !session.id.equals(message.session())) return;
        var packet = message.chunk();
        int x = packet.getX(), z = packet.getZ();
        if (!session.window.contains(x, z) || mc.getConnection() == null) return;
        long key = ChunkPos.asLong(x, z);
        LevelChunk nativeChunk = nativeChunk(session.level, x, z);
        LevelChunk chunk = nativeChunk != null ? nativeChunk : session.chunks.get(key);
        boolean first = chunk == null;
        if (first) chunk = new LevelChunk(session.level, new ChunkPos(x, z));
        var data = packet.getChunkData();
        chunk.replaceWithPacketData(data.getReadBuffer(), data.getHeightmaps(), data.getBlockEntitiesTagsConsumer(x, z));
        session.chunks.put(key, chunk);
        session.level.onChunkLoaded(chunk.getPos());
        if (first) MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
        LevelChunk ready = chunk;
        RVP_HitlClientLightAccess light = (RVP_HitlClientLightAccess) mc.getConnection();
        // 在原版 light 队列中复用两段原版处理；过期包不许重新打开已卸载区块的光照。
        session.level.queueLightUpdate(() -> {
            if (current != session || session.chunks.get(key) != ready) return;
            light.rvp$applyRemoteLight(x, z, packet.getLightData());
            light.rvp$enableRemoteLight(ready, x, z);
        });
    }

    /** 原版 getChunk 的 RETURN 兜底；原版已有有效区块时保持其优先级。 */
    public static LevelChunk lookup(ClientLevel level, int x, int z, LevelChunk original) {
        Session session = current;
        if (session == null || session.level != level || original != null && !(original instanceof EmptyLevelChunk)) return original;
        if (NATIVE_LOOKUP.get()) return original;
        LevelChunk chunk = session.chunks.get(ChunkPos.asLong(x, z));
        return chunk == null ? original : chunk;
    }

    /** 原版附近区块重新到达时更新共享引用；额外远端完整重发包则进入原有附加槽位。 */
    public static LevelChunk afterNativePacket(ClientLevel level, int x, int z, FriendlyByteBuf buffer,
            CompoundTag tag, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer, LevelChunk original) {
        Session session = current;
        long key = ChunkPos.asLong(x, z);
        if (session == null || session.level != level || !session.chunks.containsKey(key)) return original;
        if (original != null) {
            session.chunks.put(key, original);
            return original;
        }
        // 原版范围外分支尚未消费 buffer；仅补全已授权且已收到首包的区块。
        LevelChunk chunk = session.chunks.get(key);
        chunk.replaceWithPacketData(buffer, tag, consumer);
        level.onChunkLoaded(chunk.getPos());
        return chunk;
    }

    public static boolean holds(ClientLevel level, int x, int z) {
        Session session = current;
        return session != null && session.level == level && session.chunks.containsKey(ChunkPos.asLong(x, z));
    }

    /** 原版远离身体而发的 forget 不应注销导弹正在观察的同一个区块。 */
    public static boolean forgetNative(ClientLevel level, int x, int z) {
        if (!holds(level, x, z)) return false;
        level.getChunkSource().drop(x, z);
        // drop 仍释放原版槽位；unload 钩子保留远程引用，再通知依赖区块事件的渲染器。
        Session session = current;
        LevelChunk chunk = session == null ? null : session.chunks.get(ChunkPos.asLong(x, z));
        if (chunk != null) {
            level.onChunkLoaded(chunk.getPos());
            MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
        }
        return true;
    }

    /** 原版生物群系差量包在缓存范围外时应用到已授权的附加区块。 */
    public static boolean replaceRemoteBiomes(ClientLevel level, int x, int z, FriendlyByteBuf buffer) {
        if (!holds(level, x, z) || nativeChunk(level, x, z) != null) return false;
        current.chunks.get(ChunkPos.asLong(x, z)).replaceBiomes(buffer);
        return true;
    }

    /** 已见实体短暂缺包时保留镜头，最多 60 tick；真正结束由服务端结束消息立即处理。 */
    public static boolean waitForMissile(int missileId) {
        Session session = current;
        return session != null && session.missileId == missileId && ++session.missingTicks <= 60;
    }

    /** 切换到另一个导弹时撤销旧会话，重复的同弹 enter 包不重置额外地图。 */
    public static void onEnter(int missileId) {
        Session session = current;
        if (session != null && session.missileId != missileId) clear(true);
    }

    /** 清理所有额外区块；本体仍拥有的区块不卸载，远程独占的区块逐项走原版释放链。 */
    public static void clear(boolean notifyServer) {
        Session session = current;
        if (session == null) return;
        current = null;
        if (notifyServer && Minecraft.getInstance().getConnection() != null) {
            RVP_Network.CHANNEL.sendToServer(new C2SHitlRemoteHeartbeat(session.id, true));
        }
        for (long key : new ArrayList<>(session.chunks.keySet())) release(session, key);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Session session = current;
        if (session == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != session.level || mc.player == null) {
            clear(false);
            return;
        }
        session.age++;
        if (RVP_ClientHitlState.getActiveMissileId() != session.missileId) {
            if (session.age > 20) clear(true);
            return;
        }
        var missile = session.level.getEntity(session.missileId);
        if (missile != null) {
            if (!missile.getUUID().equals(session.missileUuid)) {
                clear(true);
                RVP_ClientHitlState.endFeed(session.missileId);
                return;
            }
            session.missingTicks = 0;
        }
        if (session.age % 10 == 0) {
            RVP_Network.CHANNEL.sendToServer(new C2SHitlRemoteHeartbeat(session.id, false));
        }
    }

    /** 在 setupRender HEAD 移动地形网格；相机仍由既有 CameraTVMissileMixin 更新。 */
    public static void prepareTerrain(LevelRenderer renderer, Camera camera) {
        Session session = current;
        Minecraft mc = Minecraft.getInstance();
        boolean active = session != null && mc.level == session.level && mc.player != null
                && RVP_ClientHitlState.getActiveMissileId() == session.missileId && mc.options.getCameraType().isFirstPerson();
        if (!active) {
            if (terrainWasRemote) renderer.allChanged();
            terrainWasRemote = false;
            lastArea = null;
            return;
        }
        RVP_HitlTerrainAccess access = (RVP_HitlTerrainAccess) renderer;
        if (access.rvp$viewDistance() != mc.options.getEffectiveRenderDistance()) renderer.allChanged();
        ViewArea area = access.rvp$viewArea();
        if (area == null) return;
        Vec3 lens = camera.getPosition();
        int x = SectionPos.posToSectionCoord(lens.x), y = SectionPos.posToSectionCoord(lens.y), z = SectionPos.posToSectionCoord(lens.z);
        boolean moved = area != lastArea || x != lastX || y != lastY || z != lastZ;
        access.rvp$positionTerrain(lens, mc.player.position(), moved);
        lastArea = area;
        lastX = x;
        lastY = y;
        lastZ = z;
        terrainWasRemote = true;
    }

    private static LevelChunk nativeChunk(ClientLevel level, int x, int z) {
        boolean before = NATIVE_LOOKUP.get();
        NATIVE_LOOKUP.set(true);
        try {
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
            return chunk instanceof EmptyLevelChunk ? null : chunk;
        } finally {
            NATIVE_LOOKUP.set(before);
        }
    }

    private static void release(Session session, long key) {
        LevelChunk chunk = session.chunks.remove(key);
        if (chunk == null || Minecraft.getInstance().level != session.level
                || nativeChunk(session.level, ChunkPos.getX(key), ChunkPos.getZ(key)) != null) return;
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
        session.level.unload(chunk);
        session.level.queueLightUpdate(() -> {
            // 新窗口/原版首包可能已经重新提供同坐标区块，不能让旧卸载任务清掉新光照。
            if (session.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), false) != null) return;
            var light = session.level.getLightEngine();
            ChunkPos pos = new ChunkPos(key);
            light.setLightEnabled(pos, false);
            for (int y = light.getMinLightSection(); y < light.getMaxLightSection(); y++) {
                light.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, y), null);
                light.queueSectionData(LightLayer.SKY, SectionPos.of(pos, y), null);
            }
            for (int y = session.level.getMinSection(); y < session.level.getMaxSection(); y++) {
                light.updateSectionStatus(SectionPos.of(pos, y), true);
            }
        });
    }

    private static final class Session {
        /** 当前会话/导弹/世界身份，防止 entity ID 复用。 */
        final UUID id, missileUuid;
        final int missileId;
        final ClientLevel level;
        /** 由服务端给定的窗口以及后台区块编译线程可安全读取的地图。 */
        volatile RVP_HitlRemoteWindow window;
        final Map<Long, LevelChunk> chunks = new ConcurrentHashMap<>();
        /** 客户端心跳计时与短暂实体缺包宽限。 */
        int age, missingTicks;

        Session(S2CHitlRemoteView message, ClientLevel level) {
            id = message.session();
            missileUuid = message.missileUuid();
            missileId = message.missileId();
            this.level = level;
            window = new RVP_HitlRemoteWindow(message.x(), message.z(), message.radius());
        }
    }
}
