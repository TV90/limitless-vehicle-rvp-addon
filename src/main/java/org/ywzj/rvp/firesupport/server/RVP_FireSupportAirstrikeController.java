package org.ywzj.rvp.firesupport.server;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.delivery.RVP_AirLaunchedProjectileDelivery;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;
import org.ywzj.rvp.firesupport.schedule.RVP_FireSupportSchedulePlanner;
import org.ywzj.rvp.util.RVP_ChunkPathLoadManager;
import org.ywzj.rvp.util.RVP_ChunkPathLoader;
import org.ywzj.vehicle.api.event.VehicleMoveEvent;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

/** 服务端空中支援控制器：生成真实飞机并以插件运动学推进连续固定翼航线。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportAirstrikeController {
    /** 按服务器实例隔离任务航线状态；状态只缓存飞机 UUID 和不可变路线数据。 */
    private static final Map<MinecraftServer, Map<UUID, RouteState>> STATES = new IdentityHashMap<>();
    /** 空中飞机生成与生命周期诊断日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 空中任务安全超时窗口，单位 Tick。 */
    private static final long AIRSTRIKE_OVERRUN_TICKS = 1200L;
    /** 加入世界后允许实体索引和本体初始化完成的观察宽限，单位 Tick。 */
    private static final long AIRCRAFT_OBSERVATION_GRACE_TICKS = 1L;
    /** 支援机路径滚动预读长度，单位 Tick。 */
    private static final int AIRCRAFT_PATH_LOOKAHEAD_TICKS = 5;
    /** 已观察存活的飞机非毁伤性失联后允许恢复的时长，单位 Tick。 */
    static final long AIRCRAFT_RECOVERY_TICKS = 200L;
    /** 写入飞机持久数据的任务标识。 */
    private static final String AIRCRAFT_MISSION_TAG = "RVPFireSupportMission";

    private RVP_FireSupportAirstrikeController() {}

    /** 空中任务控制状态。 */
    public enum Status {
        READY, WAITING, RECOVERING, AIRCRAFT_UNAVAILABLE, AIRCRAFT_SPAWN_FAILED, AIRCRAFT_DESTROYED,
        AIRCRAFT_LOST, TRAJECTORY_UNREACHABLE, SCHEDULE_TIMEOUT
    }

    /** 当前飞机实际挂架快照；不缓存实体引用以外的世界对象。 */
    public record Snapshot(AbstractVehicle aircraft, Vec3 rackPosition, Vec3 motion) {}

    /** 推进或初始化任务航线。 */
    public static Status tick(MinecraftServer server, RVP_FireSupportMission mission, long now) {
        if (!isAirMission(mission)) return Status.READY;
        if (now > safetyDeadlineTick(mission)) return Status.SCHEDULE_TIMEOUT;
        ServerLevel level = server.getLevel(mission.dimension);
        if (level == null) return Status.AIRCRAFT_UNAVAILABLE;
        Status targetStatus = prepareTargetChunks(server, level, mission);
        if (targetStatus != Status.READY) return targetStatus;

        Map<UUID, RouteState> serverStates = STATES.computeIfAbsent(server, ignored -> new LinkedHashMap<>());
        RouteState state = serverStates.get(mission.missionId);
        if (state == null) {
            state = buildRoute(server, mission, now);
            if (state == null) return Status.TRAJECTORY_UNREACHABLE;
            serverStates.put(mission.missionId, state);
        }
        if (state.aircraftUuid == null) {
            // 呼叫阶段只允许准备目标区块和冻结航线；必须等呼叫结束并进入打击阶段才从出发点生成飞机。
            if (!aircraftSpawnAllowed(mission.state, now, mission.callDeadlineTick)) return Status.WAITING;
            return spawnAircraft(server, level, mission, state, now);
        }

        Entity entity = level.getEntity(state.aircraftUuid);
        if (entity instanceof AbstractVehicle aircraft) {
            if (aircraft.isDestroyed() || state.killedObserved) return Status.AIRCRAFT_DESTROYED;
            if (aircraft.isRemoved()) return handleMissingAircraft(mission, state, now);
            state.aircraftObservedAlive = true;
            state.captureDynamics(aircraft);
            aircraft.uav = true;
            aircraft.getPersistentData().putUUID(AIRCRAFT_MISSION_TAG, mission.missionId);
            if (!advanceAircraftIfPathReady(aircraft, state, now)) {
                state.beginRecovery(now, "PATH_NOT_READY", aircraft.position());
                return state.recoveryTimedOut(now) ? logAircraftLost(mission, state, now) : Status.RECOVERING;
            }
            RVP_ChunkPathLoadManager.recordPostMoveObservation(aircraft);
            if (state.retainUntilExit && state.allDelivered() && now >= state.exitEndTick()) {
                discardCompleteAircraft(server, state, aircraft);
            }
            return Status.READY;
        }
        Status presence = classifyAircraftPresence(state.aircraftObservedAlive, state.aircraftSpawnTick, now,
                false, false, state.killedObserved);
        if (presence == Status.AIRCRAFT_SPAWN_FAILED) {
            LOGGER.warn("炮火任务 {} 空袭飞机生成后未能保持存活: aircraftId={}, uuid={}, spawnTick={}, now={}",
                    mission.missionId, state.aircraftId, state.aircraftUuid, state.aircraftSpawnTick, now);
            return presence;
        }
        if (presence == Status.AIRCRAFT_DESTROYED) return presence;
        if (!state.aircraftObservedAlive) return Status.WAITING;
        return handleMissingAircraft(mission, state, now);
    }

    /** 从指定入场点生成飞机，而不是从投放点补生成。 */
    private static Status spawnAircraft(MinecraftServer server, ServerLevel level,
                                        RVP_FireSupportMission mission, RouteState state, long now) {
        if (now > state.startTick() && !state.retimeStart(now)) return Status.TRAJECTORY_UNREACHABLE;
        Vec3 entry = state.referencePosition(state.startTick());
        ChunkPos entryChunk = new ChunkPos(BlockPos.containing(entry));
        RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus lease =
                RVP_FireSupportSpawnChunkLeaseManager.request(level, mission.missionId, entryChunk,
                        state.startTick(), state.preloadTicks(), mission.profile.limits().maxLoadedChunksPerMission(),
                        RVP_FireSupportSpawnChunkLeaseManager.LeasePurpose.LAUNCH_CANDIDATE,
                        safetyDeadlineTick(mission));
        if (lease == RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.MISSION_CHUNK_LIMIT
                || lease == RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.INVALID_REQUEST) return Status.AIRCRAFT_UNAVAILABLE;
        if (lease == RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.TIMED_OUT) return Status.SCHEDULE_TIMEOUT;
        if (now < state.startTick() || lease != RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.READY
                || !level.isPositionEntityTicking(BlockPos.containing(entry))) return Status.WAITING;
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(state.aircraftId).orElse(null);
        if (data == null) return Status.AIRCRAFT_UNAVAILABLE;
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose pose = state.initialPose(entry);
        // 调用本体载具构造方法：飞机必须从预计算入场点生成，姿态由插件航线状态初始化。
        AbstractVehicle aircraft = data.construct(level, entry, pose.pitch(), pose.yaw());
        // 飞机运动量由插件 RouteState 单独维护；实体初速度清零，避免首次路径门禁前被本体物理提前积分。
        aircraft.setDeltaMovement(Vec3.ZERO);
        aircraft.setZRot(pose.roll());
        if (!level.addFreshEntity(aircraft)) {
            LOGGER.warn("炮火任务 {} 空袭飞机加入世界失败: aircraftId={}, position={}",
                    mission.missionId, state.aircraftId, entry);
            return Status.AIRCRAFT_SPAWN_FAILED;
        }
        state.aircraftUuid = aircraft.getUUID();
        state.aircraftSpawnTick = now;
        state.aircraftObservedAlive = false;
        state.setPose(pose, now);
        state.captureDynamics(aircraft);
        aircraft.uav = true;
        aircraft.getPersistentData().putUUID(AIRCRAFT_MISSION_TAG, mission.missionId);
        // 调用本项目动态路径加载器：生成 Tick 只提交下一步路径，真正移动仍由后续 Tick 的硬门禁决定。
        prepareNextAircraftStep(aircraft, state, now);
        RVP_FireSupportSpawnChunkLeaseManager.confirmLaunch(level, mission.missionId, entryChunk);
        return Status.WAITING;
    }

    /** 纯状态判定：飞机只能在呼叫截止 Tick 到达后的打击阶段生成。 */
    static boolean aircraftSpawnAllowed(RVP_FireSupportMissionState missionState, long now, long callDeadlineTick) {
        return missionState == RVP_FireSupportMissionState.STRIKING && now >= callDeadlineTick;
    }

    /** 区分首次生成观察期、生成失败与曾存活后被击落。 */
    static Status classifyAircraftPresence(boolean observedAlive, long spawnTick, long now,
                                           boolean present, boolean removed, boolean destroyed) {
        if (present && !removed && !destroyed) return Status.READY;
        if (destroyed) return Status.AIRCRAFT_DESTROYED;
        if (!observedAlive && now <= spawnTick + AIRCRAFT_OBSERVATION_GRACE_TICKS) return Status.WAITING;
        return observedAlive ? Status.RECOVERING : Status.AIRCRAFT_SPAWN_FAILED;
    }

    /** 纯状态判定：明确毁伤优先，恢复窗口到点才转为失联终态。 */
    static Status classifyRecovery(boolean killedObserved, long recoveryStartTick, long now) {
        if (killedObserved) return Status.AIRCRAFT_DESTROYED;
        return recoveryStartTick != Long.MIN_VALUE && now >= recoveryStartTick + AIRCRAFT_RECOVERY_TICKS
                ? Status.AIRCRAFT_LOST : Status.RECOVERING;
    }

    /** 纯时间换算：恢复期间偏移随世界时间增长，使任务逻辑时间保持冻结。 */
    static long recoveryScheduleOffset(long accumulatedPauseTicks, long recoveryStartTick, long now) {
        return recoveryStartTick == Long.MIN_VALUE ? accumulatedPauseTicks
                : Math.addExact(accumulatedPauseTicks, Math.max(0L, now - recoveryStartTick));
    }

    /**
     * 计算下一步采用的冻结逻辑 Tick。路径恢复期间，即使本 Tick 已无待提交位移，预加载请求也必须
     * 继续指向最初被阻塞的同一步，不能提前覆盖成下一步路径。
     */
    static long nextLogicalStepTick(boolean recovering, boolean movementDue,
                                    long worldTick, long poseTick, long scheduleOffset) {
        if (recovering) return Math.subtractExact(worldTick, scheduleOffset);
        long movementTick = movementDue ? worldTick : Math.max(worldTick, Math.addExact(poseTick, 1L));
        return Math.subtractExact(movementTick, scheduleOffset);
    }

    /** @return 当前任务是否正等待支援机路径或实体恢复。 */
    public static boolean isRecovering(MinecraftServer server, RVP_FireSupportMission mission) {
        RouteState state = state(server, mission);
        return state != null && state.recovering();
    }

    /** @return 恢复窗口截止 Tick；未恢复时返回 Long.MAX_VALUE。 */
    public static long recoveryDeadlineTick(MinecraftServer server, RVP_FireSupportMission mission) {
        RouteState state = state(server, mission);
        return state == null || !state.recovering() ? Long.MAX_VALUE : state.recoveryDeadlineTick;
    }

    /** 消费一次恢复状态边沿，供任务管理器同步客户端。 */
    public static boolean consumeRecoveryStateChanged(MinecraftServer server, RVP_FireSupportMission mission) {
        RouteState state = state(server, mission);
        if (state == null || !state.recoveryStateChanged) return false;
        state.recoveryStateChanged = false;
        return true;
    }

    /** 把任务逻辑时间转换为包含恢复暂停的世界时间。 */
    public static long effectiveTick(MinecraftServer server, RVP_FireSupportMission mission, long logicalTick) {
        RouteState state = state(server, mission);
        return state == null ? logicalTick : Math.addExact(logicalTick, state.scheduleOffset(state.lastObservedTick));
    }

    /** 把世界时间转换为冻结恢复期间的任务逻辑时间。 */
    public static long logicalTick(MinecraftServer server, RVP_FireSupportMission mission, long worldTick) {
        RouteState state = state(server, mission);
        return state == null ? worldTick : Math.subtractExact(worldTick, state.scheduleOffset(worldTick));
    }

    /** @return 空中任务安全截止 Tick。 */
    public static long safetyDeadlineTick(RVP_FireSupportMission mission) {
        return Math.addExact(mission.acceptedTick,
                Math.addExact(mission.profile.limits().maxMissionDurationTicks(), AIRSTRIKE_OVERRUN_TICKS));
    }

    /** 判断任务是否完全由实体飞机空中投送组成。 */
    public static boolean isAirMission(RVP_FireSupportMission mission) {
        return !mission.weapons.isEmpty() && mission.weapons.stream()
                .allMatch(weapon -> weapon.delivery() instanceof RVP_AirLaunchedProjectileDelivery);
    }

    /** @return 指定轮次参考点的实际 Tick；路线未建立时回退原始 Tick。 */
    public static long nextReleaseTick(MinecraftServer server, RVP_FireSupportMission mission,
                                       int roundIndex, long fallback) {
        RouteState state = state(server, mission);
        return state == null ? fallback : state.releaseTick(roundIndex, fallback, state.lastObservedTick);
    }

    /** 返回当前 Tick 已经过参考释放点且满足原始 Tick 下限的待投送轮次。 */
    public static RVP_FireSupportSchedulePlanner.PlannedRound nextCandidateRound(
            MinecraftServer server, RVP_FireSupportMission mission, long now) {
        RouteState state = state(server, mission);
        if (state == null) return null;
        return state.nextCandidateRound(now, mission.callDeadlineTick);
    }

    /** 标记空中池中的一发已经真实生成，并在末发成功时启动直线出场计时。 */
    public static void markDelivered(MinecraftServer server, RVP_FireSupportMission mission,
                                     int roundIndex, long deliveryTick) {
        RouteState state = state(server, mission);
        if (state != null) state.markDelivered(roundIndex, deliveryTick);
    }

    /** @return 当前空中任务是否已经清空所有武器池。 */
    public static boolean allDelivered(MinecraftServer server, RVP_FireSupportMission mission) {
        RouteState state = state(server, mission);
        return state != null && state.allDelivered();
    }

    /** 按实际末发成功 Tick 计算参考出场段的结束 Tick。 */
    static long departureEndTick(long deliveryTick, double exitDistanceMeters,
                                 double carrierSpeedMetersPerTick) {
        return Math.addExact(deliveryTick,
                RVP_FireSupportAirstrikeRoutePlanner.requiredTicks(
                        exitDistanceMeters, carrierSpeedMetersPerTick));
    }

    /** 返回当前所有未投送计划中最早原始 Tick。 */
    public static long nextPendingTick(MinecraftServer server, RVP_FireSupportMission mission, long fallback) {
        RouteState state = state(server, mission);
        return state == null ? fallback : state.nextPendingTick(fallback, mission.callDeadlineTick);
    }

    /** 读取当前真实飞机和完整姿态变换后的挂架位置。 */
    public static Snapshot snapshot(MinecraftServer server, RVP_FireSupportMission mission, long sampleTick) {
        RouteState state = state(server, mission);
        if (state == null || state.aircraftUuid == null || !state.aircraftObservedAlive) return null;
        ServerLevel level = server.getLevel(mission.dimension);
        Entity entity = level == null ? null : level.getEntity(state.aircraftUuid);
        if (!(entity instanceof AbstractVehicle aircraft) || aircraft.isRemoved() || aircraft.isDestroyed()) return null;
        applyPose(aircraft, state.pose());
        return new Snapshot(aircraft, state.rackPosition(), state.pose().motion());
    }

    /** 任务终止时清理路线；被击落的飞机保留残骸，完整飞机继续飞出场。 */
    public static void finish(MinecraftServer server, RVP_FireSupportMission mission,
                              boolean aircraftDestroyed, boolean retainUntilExit) {
        Map<UUID, RouteState> serverStates = STATES.get(server);
        RouteState state = serverStates == null ? null : serverStates.get(mission.missionId);
        if (retainUntilExit && state != null && !aircraftDestroyed) {
            state.retainUntilExit = true;
            return;
        }
        if (serverStates != null) serverStates.remove(mission.missionId);
        if (state == null || state.aircraftUuid == null) return;
        ServerLevel level = server.getLevel(mission.dimension);
        Entity entity = level == null ? null : level.getEntity(state.aircraftUuid);
        if (entity != null) {
            RVP_ChunkPathLoadManager.releaseEntity(entity);
            entity.getPersistentData().remove(AIRCRAFT_MISSION_TAG);
            if (!aircraftDestroyed && !entity.isRemoved()) entity.discard();
        }
    }

    /** 本体每 Tick 物理之后的公开事件；仅在下一段全部区块可执行实体 Tick 时提交插件位移。 */
    @SubscribeEvent
    public static void onVehicleMove(VehicleMoveEvent event) {
        AbstractVehicle aircraft = event.getVehicle();
        if (!(aircraft.level() instanceof ServerLevel level)) return;
        Map<UUID, RouteState> serverStates = STATES.get(level.getServer());
        if (serverStates == null) return;
        for (RouteState state : serverStates.values()) {
            if (!aircraft.getUUID().equals(state.aircraftUuid)) continue;
            if (aircraft.isRemoved() || aircraft.isDestroyed()) return;
            state.aircraftObservedAlive = true;
            state.captureDynamics(aircraft);
            aircraft.uav = true;
            long now = level.getGameTime();
            // 调用本项目动态路径加载器：按旧姿态到下一姿态的真实线段逐区块检查，失败时保持旧姿态。
            if (!advanceAircraftIfPathReady(aircraft, state, now)) {
                state.beginRecovery(now, "PATH_NOT_READY", state.pose().position());
                applyPose(aircraft, state.pose());
            }
            RVP_ChunkPathLoadManager.recordPostMoveObservation(aircraft);
            // 目的：本体 FixedWingVehicle 的 tickPhysics 已在事件前执行；空袭航线由插件状态机独占，
            // 取消事件可让 AbstractVehicle 不再把本体物理积分结果带入下一 Tick。
            event.setCanceled(true);
            if (state.retainUntilExit && state.allDelivered()
                    && level.getGameTime() >= state.exitEndTick()) {
                discardCompleteAircraft(level.getServer(), state, aircraft);
            }
            return;
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractVehicle aircraft)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        Map<UUID, RouteState> serverStates = STATES.get(level.getServer());
        if (serverStates == null) return;
        for (RouteState state : serverStates.values()) {
            if (!aircraft.getUUID().equals(state.aircraftUuid)) continue;
            RemovalReason reason = aircraft.getRemovalReason();
            state.lastLeaveReason = reason == null ? "TRACKING_STOPPED" : reason.name();
            state.lastKnownPosition = aircraft.position();
            state.killedObserved = aircraft.isDestroyed() || reason == RemovalReason.KILLED;
            return;
        }
    }

    /** 清理任务状态已不存在但随后由区块存档重新载入的孤儿支援机。 */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractVehicle aircraft)
                || !(event.getLevel() instanceof ServerLevel level)
                || !aircraft.getPersistentData().hasUUID(AIRCRAFT_MISSION_TAG)) return;
        UUID missionId = aircraft.getPersistentData().getUUID(AIRCRAFT_MISSION_TAG);
        Map<UUID, RouteState> serverStates = STATES.get(level.getServer());
        RouteState state = serverStates == null ? null : serverStates.get(missionId);
        if (state == null || state.aircraftUuid == null || !state.aircraftUuid.equals(aircraft.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.remove(event.getServer());
    }

    /** 在路线解算前准备全部目标区块，避免高度图查询触发同步加载。 */
    private static Status prepareTargetChunks(MinecraftServer server, ServerLevel level,
                                              RVP_FireSupportMission mission) {
        long earliestStartTick = Long.MAX_VALUE;
        for (RVP_FireSupportSchedulePlanner.PlannedRound round : mission.plan.rounds()) {
            RVP_FireSupportMissionWeapon weapon = mission.weapons.get(round.munitionWeaponIndex());
            if (!(weapon.delivery() instanceof RVP_AirLaunchedProjectileDelivery delivery)) return Status.AIRCRAFT_UNAVAILABLE;
            RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data = delivery.data();
            long releaseTick = Math.addExact(mission.callDeadlineTick, round.strikeOffsetTicks());
            long flightTicks = (long) Math.ceil(data.entryDistanceMeters() / data.carrierSpeedMetersPerTick());
            earliestStartTick = Math.min(earliestStartTick, Math.subtractExact(releaseTick, flightTicks));
        }
        for (RVP_FireSupportSchedulePlanner.PlannedRound round : mission.plan.rounds()) {
            RVP_FireSupportMissionWeapon weapon = mission.weapons.get(round.munitionWeaponIndex());
            RVP_FireSupportDeliveryContext context = RVP_FireSupportMissionManager.createDeliveryContext(
                    server, level, mission, round, null, null, null, false);
            RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus lease =
                    RVP_FireSupportSpawnChunkLeaseManager.request(level, mission.missionId,
                            new ChunkPos(BlockPos.containing(context.impactPoint().x(), level.getMinBuildHeight(),
                                    context.impactPoint().z())), earliestStartTick,
                            ((RVP_AirLaunchedProjectileDelivery) weapon.delivery()).data().preloadTicks(),
                            mission.profile.limits().maxLoadedChunksPerMission(),
                            RVP_FireSupportSpawnChunkLeaseManager.LeasePurpose.TARGET,
                            safetyDeadlineTick(mission));
            if (lease == RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.MISSION_CHUNK_LIMIT
                    || lease == RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.INVALID_REQUEST) return Status.AIRCRAFT_UNAVAILABLE;
            if (lease == RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.TIMED_OUT) return Status.SCHEDULE_TIMEOUT;
            if (lease != RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus.READY
                    || !level.isPositionEntityTicking(BlockPos.containing(context.impactPoint().x(),
                    level.getMinBuildHeight(), context.impactPoint().z()))) return Status.WAITING;
        }
        return Status.READY;
    }

    private static RouteState state(MinecraftServer server, RVP_FireSupportMission mission) {
        Map<UUID, RouteState> serverStates = STATES.get(server);
        return serverStates == null ? null : serverStates.get(mission.missionId);
    }

    /**
     * 提交并检查飞机下一次真实位移线段。检查使用下一姿态的运动向量，而非上一 Tick 速度，
     * 因此高速转弯时触及的侧邻区块也必须已经获得 Ticket 并进入 entity-ticking。
     */
    private static boolean prepareNextAircraftStep(AbstractVehicle aircraft, RouteState state, long now) {
        Vec3 start = state.pose().position();
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose nextPose = state.previewNextPose(now);
        Vec3 motion = exactStepMotion(state.pose(), nextPose);
        RVP_ChunkPathLoader.PathLoadResult result = RVP_ChunkPathLoader.requestProjectedPath(
                aircraft, start, motion, AIRCRAFT_PATH_LOOKAHEAD_TICKS,
                RVP_ChunkPathLoadManager.RequestPriority.AIR_SUPPORT);
        state.lastPathLoadResult = result;
        return result.currentTickPathReady();
    }

    /** 在移动确实到期时执行硬门禁；同 Tick 的后续调用只继续预热下一步，不重复移动。 */
    private static boolean advanceAircraftIfPathReady(AbstractVehicle aircraft, RouteState state, long now) {
        boolean movementDue = state.movementDue(now);
        boolean pathReady = prepareNextAircraftStep(aircraft, state, now);
        if (!movementDue) return !state.recovering();
        if (!pathReady) return false;
        state.finishRecovery(now);
        state.advanceOneTick(now);
        applyPose(aircraft, state.pose());
        return true;
    }

    /** 纯数学辅助：返回控制器即将提交的精确位移，供高速转弯回归测试复用。 */
    static Vec3 exactStepMotion(RVP_FireSupportAirstrikeRoutePlanner.AircraftPose current,
                                RVP_FireSupportAirstrikeRoutePlanner.AircraftPose next) {
        if (current == null || next == null) return Vec3.ZERO;
        return next.position().subtract(current.position());
    }

    private static Status handleMissingAircraft(RVP_FireSupportMission mission, RouteState state, long now) {
        state.beginRecovery(now, state.lastLeaveReason, state.lastKnownPosition);
        Status status = classifyRecovery(state.killedObserved, state.recoveryStartTick, now);
        return status == Status.AIRCRAFT_LOST ? logAircraftLost(mission, state, now) : status;
    }

    private static Status logAircraftLost(RVP_FireSupportMission mission, RouteState state, long now) {
        RVP_ChunkPathLoader.PathLoadResult path = state.lastPathLoadResult;
        LOGGER.warn("炮火任务 {} 空袭飞机失联超时: aircraftId={}, uuid={}, reason={}, position={}, "
                        + "missingTicks={}, pathState={}, firstUnreadyChunk={}, plannedChunks={}, grantedChunks={}, "
                        + "readyChunks={}, budgetExhausted={}, pathTruncated={}",
                mission.missionId, state.aircraftId, state.aircraftUuid, state.lastLeaveReason,
                state.lastKnownPosition, now - state.recoveryStartTick,
                path == null ? "UNKNOWN" : path.firstUnreadyState(),
                path == null ? null : path.firstUnreadyChunk(),
                path == null ? 0 : path.plannedChunkCount(),
                path == null ? 0 : path.requestedChunkCount(),
                path == null ? 0 : path.readyChunkCount(),
                path != null && path.budgetExhausted(),
                path != null && path.projectedPathTruncated());
        return Status.AIRCRAFT_LOST;
    }

    /** 用原始空投弹道解算结果建立平滑参考航线和按武器 ID 分组的待投送池。 */
    private static RouteState buildRoute(MinecraftServer server, RVP_FireSupportMission mission, long actualStartTick) {
        RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData config = null;
        List<RVP_FireSupportSchedulePlanner.PlannedRound> rounds = mission.plan.rounds();
        ArrayList<RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget> releases = new ArrayList<>();
        ArrayList<RoundRef> refs = new ArrayList<>();
        for (RVP_FireSupportSchedulePlanner.PlannedRound round : rounds) {
            RVP_FireSupportMissionWeapon missionWeapon = mission.weapons.get(round.munitionWeaponIndex());
            if (!(missionWeapon.delivery() instanceof RVP_AirLaunchedProjectileDelivery delivery)) return null;
            if (config == null) config = delivery.data();
            RVP_FireSupportDeliveryContext context = RVP_FireSupportMissionManager.createDeliveryContext(
                    server, server.getLevel(mission.dimension), mission, round, null, null, null, false);
            RVP_AirLaunchedProjectileDelivery.AirPlan plan = delivery.resolvePlan(context);
            if (plan == null) return null;
            long releaseTick = Math.addExact(mission.callDeadlineTick, round.strikeOffsetTicks());
            releases.add(new RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget(
                    round.roundIndex(), releaseTick, plan.spawn(), plan.motion()));
            refs.add(new RoundRef(round, missionWeapon.weaponData().getWeaponId()));
        }
        if (config == null || releases.isEmpty()) return null;
        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route = RVP_FireSupportAirstrikeRoutePlanner.plan(
                releases, config.rackOffset(), config.entryDistanceMeters(), config.exitDistanceMeters(),
                config.carrierSpeedMetersPerTick(), actualStartTick);
        return route == null ? null : new RouteState(config, refs, route);
    }

    private static void applyPose(AbstractVehicle aircraft, RVP_FireSupportAirstrikeRoutePlanner.AircraftPose pose) {
        aircraft.setPos(pose.position());
        aircraft.setXRot(pose.pitch());
        aircraft.setYRot(pose.yaw());
        aircraft.setZRot(pose.roll());
        // 真实航速只保存在 RouteState；实体速度恒为零，确保本体物理不能绕开下一 Tick 的区块硬门禁。
        aircraft.setDeltaMovement(Vec3.ZERO);
    }

    private static void discardCompleteAircraft(MinecraftServer server, RouteState state, AbstractVehicle aircraft) {
        RVP_ChunkPathLoadManager.releaseEntity(aircraft);
        aircraft.getPersistentData().remove(AIRCRAFT_MISSION_TAG);
        aircraft.discard();
        Map<UUID, RouteState> states = STATES.get(server);
        if (states != null) states.values().removeIf(candidate -> candidate == state);
    }

    /** 单个原始计划轮次与对应武器池键。 */
    private record RoundRef(RVP_FireSupportSchedulePlanner.PlannedRound round, ResourceLocation weaponId) {}

    /** 一个任务的平滑航线、姿态和武器池运行态。 */
    private static final class RouteState {
        /** 配置指定的飞机资源 ID。 */
        private final ResourceLocation aircraftId;
        /** 完整空投配置。 */
        private final RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData config;
        /** 原始轮次与武器池键。 */
        private final List<RoundRef> refs;
        /** 同一 weaponId 的待投送轮次池。 */
        private final Map<ResourceLocation, ArrayDeque<RoundRef>> pools = new LinkedHashMap<>();
        /** 原始参考释放点是否已经尝试过一次；失败弹体会留在池中等待后续参考点。 */
        private final boolean[] referenceAttempted;
        /** 当前平滑参考航线。 */
        private RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route;
        /** 实际生成的飞机 UUID。 */
        private UUID aircraftUuid;
        /** 加入世界的服务器 Tick。 */
        private long aircraftSpawnTick = Long.MIN_VALUE;
        /** 是否至少观察到飞机正常存活。 */
        private boolean aircraftObservedAlive;
        /** 姿态上次推进到的世界 Tick。 */
        private long poseTick = Long.MIN_VALUE;
        /** 当前实际连续姿态。 */
        private RVP_FireSupportAirstrikeRoutePlanner.AircraftPose pose;
        /** 已完成恢复等待的累计 Tick。 */
        private long accumulatedPauseTicks;
        /** 当前恢复开始 Tick。 */
        private long recoveryStartTick = Long.MIN_VALUE;
        /** 当前恢复窗口截止 Tick。 */
        private long recoveryDeadlineTick = Long.MAX_VALUE;
        /** 控制器最近处理的世界 Tick。 */
        private long lastObservedTick;
        /** 最近一次实体离开世界的原因。 */
        private String lastLeaveReason = "UNKNOWN";
        /** 最近一次已知位置。 */
        private Vec3 lastKnownPosition = Vec3.ZERO;
        /** 是否观察到毁伤或 KILLED 移除。 */
        private boolean killedObserved;
        /** 是否发生恢复状态边沿变化。 */
        private boolean recoveryStateChanged;
        /** 任务完成后是否保留完整飞机飞出场。 */
        private boolean retainUntilExit;
        /** 最后一发真实生成的世界 Tick；未完成全部投送时为 Long.MIN_VALUE。 */
        private long departureStartTick = Long.MIN_VALUE;
        /** 参考直线出场段结束的世界 Tick；未完成全部投送时为 Long.MAX_VALUE。 */
        private long departureEndTick = Long.MAX_VALUE;
        /** 已成功投送轮次数。 */
        private int deliveredCount;
        /** 当前飞机采用的固定翼转向参数。 */
        private RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics dynamics =
                RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics.fallback();
        /** 是否已经记录过非固定翼回退诊断。 */
        private boolean fallbackDynamicsLogged;
        /** 最近一次飞机路径申请结果，用于失联超时时输出精确阻塞原因。 */
        private RVP_ChunkPathLoader.PathLoadResult lastPathLoadResult;

        private RouteState(RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData config,
                           List<RoundRef> refs,
                           RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route) {
            this.aircraftId = config.aircraftId();
            this.config = config;
            this.refs = List.copyOf(refs);
            this.route = route;
            this.referenceAttempted = new boolean[refs.size()];
            for (RoundRef ref : refs) pools.computeIfAbsent(ref.weaponId(), ignored -> new ArrayDeque<>()).add(ref);
        }

        private RVP_FireSupportAirstrikeRoutePlanner.AircraftPose initialPose(Vec3 position) {
            Vec3 direction = route.tangentAtDistance(route.distanceAt(startTick()));
            Vec3 motion = direction.scale(config.carrierSpeedMetersPerTick());
            net.minecraft.world.phys.Vec2 rotation = org.ywzj.vehicle.util.VectorUtil.vecToRot(direction);
            return new RVP_FireSupportAirstrikeRoutePlanner.AircraftPose(position, direction,
                    rotation.x, rotation.y, 0.0F, motion);
        }

        private void setPose(RVP_FireSupportAirstrikeRoutePlanner.AircraftPose next, long tick) {
            this.pose = next;
            this.poseTick = tick;
            this.lastObservedTick = Math.max(lastObservedTick, tick);
        }

        /** 预演下一次真实位移；恢复期间按当前累计暂停量保持同一个逻辑航点。 */
        private RVP_FireSupportAirstrikeRoutePlanner.AircraftPose previewNextPose(long worldTick) {
            if (pose == null) return null;
            long projectedPause = scheduleOffset(worldTick);
            if (!recovering() && poseTick < worldTick - 1L) {
                projectedPause = Math.addExact(projectedPause, worldTick - poseTick - 1L);
            }
            long logicalNext = nextLogicalStepTick(
                    recovering(), movementDue(worldTick), worldTick, poseTick, projectedPause);
            Vec3 desired = departing() ? route.direction()
                    : route.tangentAtDistance(route.distanceAt(logicalNext));
            return RVP_FireSupportAirstrikeRoutePlanner.advance(pose, desired,
                    config.carrierSpeedMetersPerTick(), dynamics());
        }

        /** 每个服务器 Tick 最多推进一步；漏 Tick 计入暂停，禁止恢复时追赶并瞬间跨越多个区块。 */
        private void advanceOneTick(long worldTick) {
            if (pose == null) setPose(initialPose(referencePosition(worldTick)), worldTick);
            if (poseTick == Long.MIN_VALUE) poseTick = worldTick;
            if (poseTick >= worldTick) return;
            long skippedTicks = Math.max(0L, worldTick - poseTick - 1L);
            if (skippedTicks > 0L) {
                accumulatedPauseTicks = Math.addExact(accumulatedPauseTicks, skippedTicks);
            }
            poseTick = worldTick - 1L;
            // 末发成功后立即进入既定出场方向；固定翼仍由 advance() 按本体转向能力渐进修正。
            pose = previewNextPose(worldTick);
            poseTick = worldTick;
            lastObservedTick = Math.max(lastObservedTick, worldTick);
        }

        /** @return 当前世界 Tick 是否尚有一步实际位移需要提交。 */
        private boolean movementDue(long worldTick) { return pose != null && poseTick < worldTick; }

        private RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics dynamics() {
            return dynamics;
        }

        /** 读取本体固定翼公开转向字段；其他载具使用插件通用回退参数。 */
        private void captureDynamics(AbstractVehicle aircraft) {
            if (aircraft instanceof FixedWingVehicle fixedWing) {
                dynamics = new RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics(
                        finitePositive(fixedWing.turnRateBySpeed, 0.4D),
                        finitePositive(fixedWing.xTurnRate, 2.0D),
                        finitePositive(fixedWing.yTurnRate, 3.0D),
                        finitePositive(fixedWing.zTurnRate, 8.0D));
            } else if (!fallbackDynamicsLogged) {
                fallbackDynamicsLogged = true;
                LOGGER.warn("空袭飞机 {} 不是 FixedWingVehicle，任务使用插件通用固定翼转向参数",
                        aircraft.getType());
            }
        }

        private static double finitePositive(double value, double fallback) {
            return Double.isFinite(value) && value > 0.0D ? value : fallback;
        }

        private Vec3 referencePosition(long tick) { return route.positionAt(logicalTick(tick)); }

        private Vec3 rackPosition() {
            return transformRack(pose, config.rackOffset());
        }

        private long releaseTick(int roundIndex, long fallback, long now) {
            return Math.addExact(route.releaseTick(roundIndex, fallback), scheduleOffset(now));
        }

        private long startTick() { return route.startTick(); }
        /** 返回完整飞机应结束直线出场并被移除的世界 Tick。 */
        private long exitEndTick() {
            return departureEndTick == Long.MAX_VALUE
                    ? Math.addExact(route.endTick(), scheduleOffset(lastObservedTick))
                    : departureEndTick;
        }
        private int preloadTicks() { return config.preloadTicks(); }
        private RVP_FireSupportAirstrikeRoutePlanner.AircraftPose pose() { return pose; }
        private boolean recovering() { return recoveryStartTick != Long.MIN_VALUE; }
        /** @return 是否已经进入末发后的直线出场阶段。 */
        private boolean departing() { return departureStartTick != Long.MIN_VALUE; }

        private void beginRecovery(long now, String reason, Vec3 position) {
            lastObservedTick = Math.max(lastObservedTick, now);
            // 未就绪 Tick 必须只推进时间戳、不推进坐标，避免恢复后补走多步并跨过未验证区块。
            poseTick = Math.max(poseTick, now);
            if (recovering()) return;
            recoveryStartTick = now;
            recoveryDeadlineTick = Math.addExact(now, AIRCRAFT_RECOVERY_TICKS);
            recoveryStateChanged = true;
            lastLeaveReason = reason == null ? "UNKNOWN" : reason;
            if (position != null) lastKnownPosition = position;
        }

        private void finishRecovery(long now) {
            lastObservedTick = Math.max(lastObservedTick, now);
            if (!recovering()) return;
            accumulatedPauseTicks = Math.addExact(accumulatedPauseTicks, now - recoveryStartTick);
            recoveryStartTick = Long.MIN_VALUE;
            recoveryDeadlineTick = Long.MAX_VALUE;
            recoveryStateChanged = true;
            lastLeaveReason = "UNKNOWN";
            killedObserved = false;
        }

        private boolean recoveryTimedOut(long now) { return recovering() && now >= recoveryDeadlineTick; }
        private long scheduleOffset(long now) { return recoveryScheduleOffset(accumulatedPauseTicks, recoveryStartTick, now); }
        private long logicalTick(long worldTick) { return Math.subtractExact(worldTick, scheduleOffset(worldTick)); }

        private RVP_FireSupportSchedulePlanner.PlannedRound nextCandidateRound(long now, long callDeadlineTick) {
            long logicalNow = logicalTick(now);
            for (int index = 0; index < refs.size(); index++) {
                if (referenceAttempted[index]) continue;
                RVP_FireSupportAirstrikeRoutePlanner.ScheduledRelease anchor = route.releases().get(index);
                if (logicalNow < anchor.actualTick()) continue;
                RoundRef anchorRef = refs.get(index);
                ArrayDeque<RoundRef> pool = pools.get(anchorRef.weaponId());
                if (pool == null || pool.isEmpty()) continue;
                RoundRef candidate = pool.peekFirst();
                long planned = Math.addExact(callDeadlineTick, candidate.round().strikeOffsetTicks());
                if (planned <= logicalNow) {
                    // 调用方随后会在同一 Tick 执行 prepare/deliver；标记后允许失败弹体由后续参考点重试。
                    referenceAttempted[index] = true;
                    return candidate.round();
                }
            }
            // 所有参考点都尝试过后进入出场延长段，继续清空尚未成功的武器池。
            for (ArrayDeque<RoundRef> pool : pools.values()) {
                RoundRef candidate = pool.peekFirst();
                if (candidate == null) continue;
                long planned = Math.addExact(callDeadlineTick, candidate.round().strikeOffsetTicks());
                if (planned <= logicalNow) return candidate.round();
            }
            return null;
        }

        private void markDelivered(int roundIndex, long deliveryTick) {
            for (ArrayDeque<RoundRef> pool : pools.values()) {
                RoundRef first = pool.peekFirst();
                if (first != null && first.round().roundIndex() == roundIndex) {
                    pool.removeFirst();
                    deliveredCount++;
                    if (allDelivered() && !departing()) {
                        departureStartTick = deliveryTick;
                        departureEndTick = RVP_FireSupportAirstrikeController.departureEndTick(
                                deliveryTick, config.exitDistanceMeters(), config.carrierSpeedMetersPerTick());
                    }
                    return;
                }
            }
        }

        private boolean allDelivered() { return deliveredCount >= refs.size(); }

        private long nextPendingTick(long fallback, long callDeadlineTick) {
            long result = Long.MAX_VALUE;
            for (ArrayDeque<RoundRef> pool : pools.values()) {
                RoundRef ref = pool.peekFirst();
                if (ref != null) result = Math.min(result,
                        Math.addExact(callDeadlineTick, ref.round().strikeOffsetTicks()));
            }
            return result == Long.MAX_VALUE ? fallback : result;
        }

        private boolean retimeStart(long startTick) {
            ArrayList<RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget> targets = new ArrayList<>();
            for (RVP_FireSupportAirstrikeRoutePlanner.ScheduledRelease release : route.releases()) {
                targets.add(new RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget(release.roundIndex(),
                        release.plannedTick(), release.releasePosition(), route.direction()));
            }
            RVP_FireSupportAirstrikeRoutePlanner.RoutePlan replanned =
                    RVP_FireSupportAirstrikeRoutePlanner.plan(targets, config.rackOffset(),
                            config.entryDistanceMeters(), config.exitDistanceMeters(),
                            config.carrierSpeedMetersPerTick(), startTick);
            if (replanned == null) return false;
            route = replanned;
            return true;
        }
    }

    /** 根据当前完整姿态把本地挂架偏移变换为世界坐标。 */
    private static Vec3 transformRack(RVP_FireSupportAirstrikeRoutePlanner.AircraftPose pose,
                                      RVP_FireSupportDeliveryTypes.LocalOffset offset) {
        if (pose == null) return Vec3.ZERO;
        Vec3 forward = pose.forward().normalize();
        Vec3 horizontalForward = new Vec3(forward.x(), 0.0D, forward.z());
        Vec3 right = horizontalForward.lengthSqr() > 1.0E-8D
                ? new Vec3(horizontalForward.z(), 0.0D, -horizontalForward.x()).normalize()
                : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 up = forward.cross(right).normalize();
        double roll = Math.toRadians(pose.roll());
        Vec3 rolledRight = rotateAroundAxis(right, forward, roll);
        Vec3 rolledUp = rotateAroundAxis(up, forward, roll);
        return pose.position().add(rolledRight.scale(offset.x()))
                .add(rolledUp.scale(offset.y())).add(forward.scale(offset.z()));
    }

    private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 axis, double angle) {
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        return vector.scale(cosine).add(axis.cross(vector).scale(sine))
                .add(axis.scale(axis.dot(vector) * (1.0D - cosine)));
    }
}
