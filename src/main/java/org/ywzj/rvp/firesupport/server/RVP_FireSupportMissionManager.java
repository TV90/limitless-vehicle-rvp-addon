package org.ywzj.rvp.firesupport.server;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.firesupport.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.firesupport.RVP_FireSupportImpactPoint;
import org.ywzj.rvp.firesupport.RVP_FireSupportPattern;
import org.ywzj.rvp.firesupport.RVP_FireSupportProfileManager;
import org.ywzj.rvp.firesupport.RVP_FireSupportRequest;
import org.ywzj.rvp.firesupport.RVP_FireSupportSchedulePlanner;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.firesupport.S2CFireSupportMissionUpdate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 服务端权威任务管理器：按游戏 Tick 公平推进呼叫、打击、失败重试与延迟停火。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportMissionManager {
    /** 每 Tick 最多成功生成的顶层炮火弹体数。 */ public static final int MAX_SPAWNS_PER_TICK = 8;
    /** 同一计划弹的最大连续生成失败次数。 */ public static final int MAX_DELIVERY_FAILURES = 3;
    /** 每名玩家保留的最近请求 nonce 数。 */ private static final int MAX_NONCES_PER_PLAYER = 64;
    /** nonce 结果保留时间，单位 Tick。 */ private static final int NONCE_TTL_TICKS = 1200;
    /** 终态任务供断线重连补发的保留时间，单位 Tick。 */ private static final int MISSION_HISTORY_TTL_TICKS = 1200;
    /** 管理器日志。 */ private static final Logger LOGGER = LogUtils.getLogger();
    /** 按服务器实例隔离任务、冷却和幂等缓存。 */ private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();

    private RVP_FireSupportMissionManager() {}

    /** 处理一次新呼叫；重复 nonce 同内容返回第一次结果，不会创建第二个任务。 */
    public static SubmissionResult submit(ServerPlayer player, RVP_FireSupportRequest request) {
        if (player == null || request == null || request.nonce() == null) {
            return SubmissionResult.rejected(request == null ? new UUID(0, 0) : request.nonce(),
                    RVP_FireSupportEndReason.INVALID_PACKET);
        }
        MinecraftServer server = player.server;
        ServerState state = STATES.computeIfAbsent(server, ignored -> new ServerState());
        long now = player.serverLevel().getGameTime();
        pruneNonces(state, now);
        LinkedHashMap<UUID, NonceEntry> playerNonces = state.requestNonces.computeIfAbsent(
                player.getUUID(), ignored -> new LinkedHashMap<>());
        NonceEntry previous = playerNonces.get(request.nonce());
        if (previous != null) {
            return previous.request.equals(request) ? previous.result
                    : SubmissionResult.rejected(request.nonce(), RVP_FireSupportEndReason.NONCE_CONFLICT);
        }

        int activeForPlayer = (int) state.missions.values().stream()
                .filter(mission -> !mission.terminal() && mission.ownerId.equals(player.getUUID())).count();
        int activeGlobal = (int) state.missions.values().stream().filter(mission -> !mission.terminal()).count();
        long cooldownUntil = state.cooldownUntil.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        // 调用本项目请求验证器，把不可信选择转换为冻结计划并执行 holder、revision、距离和预算校验。
        RVP_FireSupportRequestValidator.ValidationResult validation = RVP_FireSupportRequestValidator.validate(
                player, request, RVP_FireSupportProfileManager.INSTANCE.snapshot(), activeForPlayer,
                activeGlobal, cooldownUntil);
        SubmissionResult result;
        if (!validation.isAccepted()) {
            result = SubmissionResult.rejected(request.nonce(), validation.reason());
        } else {
            RVP_FireSupportRequestValidator.Accepted accepted = validation.accepted();
            UUID missionId = UUID.randomUUID();
            String ownerName = sanitizeOwnerName(player.getGameProfile().getName());
            RVP_FireSupportMission mission = new RVP_FireSupportMission(missionId, player.getUUID(), ownerName,
                    accepted.terminalId(), player.level().dimension(), accepted.profileId(), request.revision(),
                    accepted.profile(), accepted.munition(), accepted.mode(), accepted.pattern(), accepted.delivery(),
                    accepted.weaponData(), accepted.parameters(), request.targetX(), request.targetZ(),
                    accepted.normalizedHeading(), accepted.seed(), now, accepted.plan());
            state.missions.put(missionId, mission);
            state.cooldownUntil.put(player.getUUID(), Math.addExact(now,
                    accepted.profile().limits().requestCooldownTicks()));
            result = SubmissionResult.accepted(request.nonce(), mission);
            sendUpdate(server, mission);
        }
        playerNonces.put(request.nonce(), new NonceEntry(request, result, now));
        trimOldest(playerNonces, MAX_NONCES_PER_PLAYER);
        return result;
    }

    /** 处理独立停火请求；首次有效请求固定最早生效 Tick，重复请求不能改变倒计时。 */
    public static CeaseFireResult requestCeaseFire(ServerPlayer player, UUID missionId, UUID nonce) {
        if (player == null || missionId == null || nonce == null) {
            return new CeaseFireResult(false, RVP_FireSupportEndReason.INVALID_PACKET, Long.MAX_VALUE);
        }
        ServerState state = STATES.computeIfAbsent(player.server, ignored -> new ServerState());
        long now = player.serverLevel().getGameTime();
        pruneNonces(state, now);
        LinkedHashMap<UUID, CeaseNonceEntry> playerNonces = state.ceaseNonces.computeIfAbsent(
                player.getUUID(), ignored -> new LinkedHashMap<>());
        CeaseNonceEntry previous = playerNonces.get(nonce);
        if (previous != null) {
            return previous.missionId.equals(missionId) ? previous.result
                    : new CeaseFireResult(false, RVP_FireSupportEndReason.NONCE_CONFLICT, Long.MAX_VALUE);
        }
        RVP_FireSupportMission mission = state == null ? null : state.missions.get(missionId);
        CeaseFireResult result;
        if (mission == null || !mission.ownerId.equals(player.getUUID())
                || (mission.state != RVP_FireSupportMissionState.STRIKING
                && mission.state != RVP_FireSupportMissionState.CEASE_FIRE_PENDING)) {
            result = new CeaseFireResult(false, RVP_FireSupportEndReason.CEASE_FIRE_NOT_ALLOWED, Long.MAX_VALUE);
        } else if (!RVP_FireSupportTerminalIdentity.matchesBoundTerminalInAllowedHand(
                player, mission.terminalInstanceId, mission.profile.holderPolicy())) {
            result = new CeaseFireResult(false, RVP_FireSupportEndReason.TERMINAL_NOT_HELD, Long.MAX_VALUE);
        } else {
            if (mission.state == RVP_FireSupportMissionState.STRIKING) {
                mission.state = RVP_FireSupportMissionState.CEASE_FIRE_PENDING;
                mission.ceaseFireEffectiveTick = Math.addExact(now,
                        mission.profile.strikeStage().ceaseFireDelayTicks());
                sendUpdate(player.server, mission);
            }
            result = new CeaseFireResult(true, RVP_FireSupportEndReason.NONE, mission.ceaseFireEffectiveTick);
        }
        playerNonces.put(nonce, new CeaseNonceEntry(missionId, result, now));
        trimOldest(playerNonces, MAX_NONCES_PER_PLAYER);
        return result;
    }

    /** @return 指定服务器当前活动任务的只读快照，供诊断和测试使用。 */
    public static List<MissionView> activeMissions(MinecraftServer server) {
        ServerState state = STATES.get(server);
        if (state == null) return List.of();
        return state.missions.values().stream().filter(mission -> !mission.terminal())
                .map(MissionView::from).toList();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) processTick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.remove(event.getServer());
    }

    /** 死亡事件即时锁定呼叫阶段取消语义，避免 keepInventory/快速重生跨过 Tick 检查。 */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        cancelCallingForPlayer(player.server, player.getUUID(), RVP_FireSupportEndReason.PLAYER_DIED, true);
    }

    /** 断线事件按冻结 profile 策略即时取消呼叫；打击阶段任务保持完全解耦。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        cancelCallingForPlayer(player.server, player.getUUID(), RVP_FireSupportEndReason.PLAYER_DISCONNECTED, false);
    }

    /** 玩家重连时补发仍在执行和近期终态任务，保持打击阶段断线后的状态连续性。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerState state = STATES.get(player.server);
        if (state == null) return;
        pruneNonces(state, player.serverLevel().getGameTime());
        state.missions.values().stream().filter(mission -> mission.ownerId.equals(player.getUUID()))
                .map(MissionView::from).forEach(view -> sendView(player, view));
        state.recentMissions.values().stream().map(HistoryEntry::view)
                .filter(view -> view.ownerId().equals(player.getUUID())).forEach(view -> sendView(player, view));
    }

    private static void processTick(MinecraftServer server) {
        ServerState state = STATES.get(server);
        if (state == null) return;
        long now = server.overworld().getGameTime();
        if (state.missions.isEmpty()) {
            pruneNonces(state, now);
            return;
        }
        List<RVP_FireSupportMission> missions = new ArrayList<>(state.missions.values());
        int start = Math.floorMod(state.rotationCursor, missions.size());
        int spawned = 0;
        for (int offset = 0; offset < missions.size(); offset++) {
            RVP_FireSupportMission mission = missions.get((start + offset) % missions.size());
            if (mission.state == RVP_FireSupportMissionState.CALLING) {
                if (!guardCalling(server, mission)) continue;
                if (now >= mission.callDeadlineTick) {
                    mission.state = RVP_FireSupportMissionState.STRIKING;
                    sendUpdate(server, mission);
                }
            }
            if ((mission.state == RVP_FireSupportMissionState.STRIKING
                    || mission.state == RVP_FireSupportMissionState.CEASE_FIRE_PENDING)
                    && now >= mission.ceaseFireEffectiveTick) {
                finish(server, mission, RVP_FireSupportMissionState.CEASED, RVP_FireSupportEndReason.CEASED);
                continue;
            }
            if (spawned >= MAX_SPAWNS_PER_TICK || mission.state == RVP_FireSupportMissionState.CALLING
                    || mission.terminal() || now < mission.nextSpawnTick()) continue;
            if (deliverOne(server, mission, now)) spawned++;
        }
        if (!missions.isEmpty()) state.rotationCursor = (start + 1) % missions.size();
        Iterator<RVP_FireSupportMission> iterator = state.missions.values().iterator();
        while (iterator.hasNext()) if (iterator.next().terminal()) iterator.remove();
        pruneNonces(state, now);
    }

    private static void cancelCallingForPlayer(MinecraftServer server, UUID playerId,
                                               RVP_FireSupportEndReason reason, boolean death) {
        ServerState state = STATES.get(server);
        if (state == null) return;
        for (RVP_FireSupportMission mission : state.missions.values()) {
            if (mission.state != RVP_FireSupportMissionState.CALLING || !mission.ownerId.equals(playerId)) continue;
            boolean enabled = death ? mission.profile.callStage().cancelOnPlayerDeath()
                    : mission.profile.callStage().cancelOnDisconnect();
            if (enabled) finish(server, mission, RVP_FireSupportMissionState.CANCELLED, reason);
        }
    }

    /** @return 呼叫是否仍有效；失败时已原子取消并广播。 */
    private static boolean guardCalling(MinecraftServer server, RVP_FireSupportMission mission) {
        ServerPlayer player = server.getPlayerList().getPlayer(mission.ownerId);
        if (player == null) {
            if (mission.profile.callStage().cancelOnDisconnect()) {
                finish(server, mission, RVP_FireSupportMissionState.CANCELLED,
                        RVP_FireSupportEndReason.PLAYER_DISCONNECTED);
                return false;
            }
            return true;
        }
        if (mission.profile.callStage().cancelOnPlayerDeath() && (!player.isAlive() || player.isDeadOrDying())) {
            finish(server, mission, RVP_FireSupportMissionState.CANCELLED, RVP_FireSupportEndReason.PLAYER_DIED);
            return false;
        }
        if (mission.profile.callStage().cancelOnTerminalLost()
                && !RVP_FireSupportTerminalIdentity.ownsUniqueTerminal(player, mission.terminalInstanceId)) {
            finish(server, mission, RVP_FireSupportMissionState.CANCELLED, RVP_FireSupportEndReason.TERMINAL_LOST);
            return false;
        }
        return true;
    }

    /** @return 本 Tick 是否成功生成一发并消耗全局发射预算。 */
    private static boolean deliverOne(MinecraftServer server, RVP_FireSupportMission mission, long now) {
        ServerLevel level = server.getLevel(mission.dimension);
        if (level == null) {
            finish(server, mission, RVP_FireSupportMissionState.FAILED, RVP_FireSupportEndReason.OUTSIDE_WORLD);
            return false;
        }
        RVP_FireSupportSchedulePlanner.PlannedRound round = mission.plan.rounds().get(mission.nextRoundIndex);
        RVP_FireSupportDeliveryResult result;
        try {
            LivingEntity owner = resolveOwner(server, level, mission);
            // 调用阶段 A 几何实现：按冻结 seed、全局轮次和规范化参数计算本发权威落点。
            RVP_FireSupportImpactPoint impact = mission.patternPreset.pattern().resolve(new RVP_FireSupportPattern.Context(
                    mission.targetX, mission.targetZ, mission.headingDegrees, round.roundIndex(),
                    mission.plan.rounds().size(), mission.authoritativeSeed, mission.parameters,
                    mission.fireMode.dispersionMultiplier()));
            // 调用阶段 B 类型化投送器：租约就绪后生成真实 RVP 弹体并沿用既有生命周期。
            result = mission.delivery.deliver(new RVP_FireSupportDeliveryContext(level, owner, mission.weaponData,
                    impact, mission.missionId, round.roundIndex(), mission.authoritativeSeed,
                    mission.nextSpawnTick(), mission.profile.limits().maxLoadedChunksPerMission()));
        } catch (RuntimeException exception) {
            LOGGER.error("炮火任务 {} 第 {} 发投送异常", mission.missionId, round.roundIndex(), exception);
            result = new RVP_FireSupportDeliveryResult(RVP_FireSupportDeliveryResult.Status.SPAWN_FAILED, null, null);
        }
        if (result.delivered()) {
            mission.nextRoundIndex++;
            mission.consecutiveFailures = 0;
            if (mission.nextRoundIndex >= mission.plan.rounds().size()) {
                finish(server, mission, RVP_FireSupportMissionState.COMPLETED, RVP_FireSupportEndReason.COMPLETED);
            } else {
                sendUpdate(server, mission);
            }
            return true;
        }
        switch (result.status()) {
            case TOO_EARLY, WAITING_FOR_CHUNK -> { return false; }
            case CHUNK_LIMIT_EXCEEDED -> finish(server, mission, RVP_FireSupportMissionState.FAILED,
                    RVP_FireSupportEndReason.CHUNK_LIMIT);
            case CHUNK_WAIT_TIMED_OUT -> finish(server, mission, RVP_FireSupportMissionState.FAILED,
                    RVP_FireSupportEndReason.CHUNK_TIMEOUT);
            case OUTSIDE_WORLD_BORDER, OUTSIDE_BUILD_HEIGHT -> finish(server, mission,
                    RVP_FireSupportMissionState.FAILED, RVP_FireSupportEndReason.OUTSIDE_WORLD);
            case UNSUPPORTED_WEAPON, INVALID_CONTEXT -> finish(server, mission,
                    RVP_FireSupportMissionState.FAILED, RVP_FireSupportEndReason.DELIVERY_UNSUPPORTED);
            case SPAWN_FAILED -> {
                if (++mission.consecutiveFailures >= MAX_DELIVERY_FAILURES) finish(server, mission,
                        RVP_FireSupportMissionState.FAILED, RVP_FireSupportEndReason.DELIVERY_FAILED);
            }
            case DELIVERED -> { }
        }
        return false;
    }

    private static LivingEntity resolveOwner(MinecraftServer server, ServerLevel level,
                                              RVP_FireSupportMission mission) {
        ServerPlayer online = server.getPlayerList().getPlayer(mission.ownerId);
        if (online != null && online.level() == level && online.isAlive()) return online;
        // 调用 Forge 假玩家工厂仅重建离线伤害归属实体；不伪造载具或武器站，也不缓存可变 Player。
        return FakePlayerFactory.get(level, new GameProfile(mission.ownerId, mission.ownerName));
    }

    private static void finish(MinecraftServer server, RVP_FireSupportMission mission,
                               RVP_FireSupportMissionState state, RVP_FireSupportEndReason reason) {
        mission.state = state;
        mission.endReason = reason;
        // 调用阶段 B 租约管理器：所有终态停止刷新该任务的短期 Chunk Ticket。
        RVP_FireSupportSpawnChunkLeaseManager.releaseMission(server, mission.missionId);
        ServerState serverState = STATES.get(server);
        if (serverState != null) serverState.recentMissions.put(mission.missionId,
                new HistoryEntry(MissionView.from(mission), server.overworld().getGameTime()));
        sendUpdate(server, mission);
    }

    private static void sendUpdate(MinecraftServer server, RVP_FireSupportMission mission) {
        ServerPlayer owner = server.getPlayerList().getPlayer(mission.ownerId);
        if (owner != null) sendView(owner, MissionView.from(mission));
    }

    private static void sendView(ServerPlayer owner, MissionView view) {
        // 调用本项目网络通道，只向任务所有者发送低频权威状态，避免泄漏全服目标坐标。
        RVP_Network.CHANNEL.sendTo(S2CFireSupportMissionUpdate.from(view), owner.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    private static void pruneNonces(ServerState state, long now) {
        state.requestNonces.values().forEach(map -> map.entrySet().removeIf(
                entry -> now - entry.getValue().createdTick > NONCE_TTL_TICKS));
        state.requestNonces.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        state.ceaseNonces.values().forEach(map -> map.entrySet().removeIf(
                entry -> now - entry.getValue().createdTick > NONCE_TTL_TICKS));
        state.ceaseNonces.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        state.recentMissions.entrySet().removeIf(entry ->
                now - entry.getValue().completedTick > MISSION_HISTORY_TTL_TICKS);
    }

    private static <K, V> void trimOldest(LinkedHashMap<K, V> map, int max) {
        while (map.size() > max) map.remove(map.keySet().iterator().next());
    }

    private static String sanitizeOwnerName(String name) {
        if (name == null || name.isBlank()) return "RVPFireSupport";
        return name.length() <= 16 ? name : name.substring(0, 16);
    }

    /** C2S 请求的稳定响应摘要。 */
    public record SubmissionResult(
            /** 对应请求 nonce。 */ UUID nonce,
            /** 是否创建了任务。 */ boolean accepted,
            /** 拒绝原因；接受时为 NONE。 */ RVP_FireSupportEndReason reason,
            /** 接受时的任务 ID。 */ UUID missionId,
            /** 接受时的权威随机种子。 */ long authoritativeSeed,
            /** 规范化动态参数。 */ Map<String, Double> parameters,
            /** 呼叫截止 Tick。 */ long callDeadlineTick,
            /** 首发绝对计划 Tick。 */ long firstRoundTick,
            /** 末发绝对计划 Tick。 */ long lastRoundTick) {
        public SubmissionResult { parameters = Map.copyOf(parameters); }
        static SubmissionResult rejected(UUID nonce, RVP_FireSupportEndReason reason) {
            return new SubmissionResult(nonce, false, reason, new UUID(0, 0), 0, Map.of(), 0, 0, 0);
        }
        static SubmissionResult accepted(UUID nonce, RVP_FireSupportMission mission) {
            long first = mission.callDeadlineTick + mission.plan.rounds().get(0).strikeOffsetTicks();
            long last = mission.acceptedTick + mission.plan.lastRoundFromAcceptanceTicks();
            return new SubmissionResult(nonce, true, RVP_FireSupportEndReason.NONE, mission.missionId,
                    mission.authoritativeSeed, mission.parameters, mission.callDeadlineTick, first, last);
        }
    }

    /** 停火请求即时响应。 */
    public record CeaseFireResult(
            /** 服务端是否接受。 */ boolean accepted,
            /** 拒绝原因；接受时为 NONE。 */ RVP_FireSupportEndReason reason,
            /** 接受时的权威停火生效 Tick。 */ long effectiveTick) {}

    /** 对诊断公开的不可变活动任务摘要。 */
    public record MissionView(
            /** 任务 ID。 */ UUID missionId,
            /** 发起者 UUID。 */ UUID ownerId,
            /** 当前权威阶段。 */ RVP_FireSupportMissionState state,
            /** 终态或异常原因。 */ RVP_FireSupportEndReason reason,
            /** 已成功生成弹数。 */ int deliveredRounds,
            /** 总计划弹数。 */ int totalRounds,
            /** 下一发权威 Tick。 */ long nextRoundTick,
            /** 停火生效 Tick。 */ long ceaseFireEffectiveTick) {
        public static MissionView from(RVP_FireSupportMission mission) {
            return new MissionView(mission.missionId, mission.ownerId, mission.state, mission.endReason, mission.nextRoundIndex,
                    mission.plan.rounds().size(), mission.nextSpawnTick(), mission.ceaseFireEffectiveTick);
        }
    }

    /** 单服务器全部运行态。 */
    private static final class ServerState {
        /** 按创建顺序保存的活动任务。 */ private final Map<UUID, RVP_FireSupportMission> missions = new LinkedHashMap<>();
        /** 每名玩家下次允许成功请求的 Tick。 */ private final Map<UUID, Long> cooldownUntil = new LinkedHashMap<>();
        /** 每名玩家最近请求的幂等结果。 */ private final Map<UUID, LinkedHashMap<UUID, NonceEntry>> requestNonces = new LinkedHashMap<>();
        /** 每名玩家最近停火请求的幂等结果。 */ private final Map<UUID, LinkedHashMap<UUID, CeaseNonceEntry>> ceaseNonces = new LinkedHashMap<>();
        /** 近期终态任务，用于断线重连后补发最终状态。 */ private final Map<UUID, HistoryEntry> recentMissions = new LinkedHashMap<>();
        /** 跨 Tick 公平轮转起点。 */ private int rotationCursor;
    }

    /** 一条有界 nonce 缓存记录。 */
    private record NonceEntry(
            /** 原始不可变请求，用于检测同 nonce 篡改内容。 */ RVP_FireSupportRequest request,
            /** 第一次处理产生的稳定结果。 */ SubmissionResult result,
            /** 记录创建 Tick。 */ long createdTick) {}

    /** 一条停火 nonce 缓存记录。 */
    private record CeaseNonceEntry(
            /** nonce 首次绑定的任务 ID。 */ UUID missionId,
            /** 首次处理产生的稳定结果。 */ CeaseFireResult result,
            /** 记录创建 Tick。 */ long createdTick) {}

    /** 一条近期终态任务状态。 */
    private record HistoryEntry(
            /** 可直接通过网络补发的不可变任务摘要。 */ MissionView view,
            /** 进入终态的服务器 Tick。 */ long completedTick) {}
}
