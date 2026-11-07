package org.ywzj.rvp.uav;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.damagesource.DamageSource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_DeployableUavCatalog;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RVP_DeployableUavService {
    public enum DeployResult {
        SUCCESS,
        NO_PARENT_VEHICLE,
        NO_CONFIG,
        INVALID_TEMPLATE,
        ALREADY_DEPLOYED,
        COOLDOWN,
        SPAWN_FAILED,
        SEAT_NOT_ALLOWED
    }

    private RVP_DeployableUavService() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 母车实体不可用（被卸载/已销毁）时自动上车的最大重试 tick 数，3 秒。 */
    private static final int AUTO_RIDE_MAX_RETRY = 60;

    record PendingAutoRide(UUID childUuid, UUID parentUuid, ResourceLocation parentDimension,
                           ResourceLocation playerDimension, int seatIndex, int retryLeft,
                           long notBeforeTick, @Nullable ResourceLocation returnDimension,
                           @Nullable Vec3 returnPosition, boolean returned) {}

    enum PendingRideDecision { CANCEL, ALREADY_PARENT, WAIT, READY }

    /** 待自动上车队列（key = 玩家 UUID）：玩家离开无人机后，母车实体不可用时延迟重试。 */
    private static final Map<UUID, PendingAutoRide> PENDING_AUTO_RIDE = new HashMap<>();
    /** 每个服务器生命周期只恢复一次持久化请求，不能用旧 tick 延迟重连玩家。 */
    private static boolean returnsLoaded;

    static PendingRideDecision classifyPendingRide(boolean playerAlive, ResourceLocation currentDimension,
                                                  boolean mounted, boolean mountedParent,
                                                  long currentTick, PendingAutoRide pending) {
        if (!playerAlive) return PendingRideDecision.CANCEL;
        if (mounted) return mountedParent ? PendingRideDecision.ALREADY_PARENT : PendingRideDecision.CANCEL;
        if (!pending.playerDimension().equals(currentDimension)) return PendingRideDecision.CANCEL;
        return currentTick < pending.notBeforeTick() ? PendingRideDecision.WAIT : PendingRideDecision.READY;
    }

    static @Nullable PendingAutoRide decrementPendingRetry(PendingAutoRide pending) {
        int retryLeft = pending.retryLeft() - 1;
        if (retryLeft <= 0) return null;
        return new PendingAutoRide(pending.childUuid(), pending.parentUuid(), pending.parentDimension(), pending.playerDimension(),
                pending.seatIndex(), retryLeft, pending.notBeforeTick(), pending.returnDimension(),
                pending.returnPosition(), pending.returned());
    }

    /** 母车座位锁信息（玩家驾驶无人机期间锁定母车座位）。 */
    public record SeatLockInfo(int seatIndex, int ownerPlayerId, UUID ownerPlayerUuid) {}

    /** 母车座位锁注册表（key = 母车实体 UUID）。 */
    private static final Map<UUID, SeatLockInfo> PARENT_SEAT_LOCKS = new HashMap<>();

    /** 母车（父车）最近一次同步到的位置快照（key = 无人机实体 UUID）：母车区块卸载后被击毁仍能据此回传。 */
    private static final Map<UUID, Vec3> PARENT_LAST_POSITIONS = new HashMap<>();

    /** 精确保护同一tick内因部署子机毁伤回传的玩家，只匹配该child作为爆炸directEntity。 */
    private record ReturnProtection(UUID childUuid, long damageTick) {}
    private static final Map<UUID, ReturnProtection> RETURN_PROTECTION = new HashMap<>();

    public static DeployResult deployLinkedUav(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle parent)) {
            return DeployResult.NO_PARENT_VEHICLE;
        }
        return deployLinkedUav(parent, player);
    }

    public static DeployResult deployLinkedUav(AbstractVehicle parent, @Nullable LivingEntity operator) {
        if (parent == null) {
            return DeployResult.NO_PARENT_VEHICLE;
        }
        if (!(parent.level() instanceof ServerLevel serverLevel)) {
            return DeployResult.SPAWN_FAILED;
        }
        // 调用本项目候选目录：实际部署按母车当前选择解析一次，并把结果固定到子机快照。
        RVP_DeployableUavConfig config = RVP_DeployableUavCatalog.getEffectiveConfig(serverLevel, parent);
        if (!config.isConfigured()) {
            return DeployResult.NO_CONFIG;
        }
        if (operator instanceof ServerPlayer serverPlayer && !config.isSeatAllowed(findSeatIndex(parent, serverPlayer))) {
            return DeployResult.SEAT_NOT_ALLOWED;
        }
        RVP_DeployableUavStateSavedData.StateSnapshot savedState = RVP_DeployableUavStateSavedData.snapshot(
                serverLevel, parent.getUUID(), RVP_DeployableUavCatalog.getDefaultOptionId(parent.getVehicleId()));
        if (savedState.deployed() || (!savedState.lockedReason().isBlank() && !"cooldown".equals(savedState.lockedReason()))) {
            return DeployResult.ALREADY_DEPLOYED;
        }
        if (savedState.remainingCooldownTick() > 0) {
            return DeployResult.COOLDOWN;
        }
        if (config.singleInstance()) {
            AbstractVehicle existing = getLinkedChild(parent).orElse(null);
            if (existing != null && existing.isAlive() && !existing.isRemoved() && !existing.isDestroyed()) {
                return DeployResult.ALREADY_DEPLOYED;
            }
            // Entity 不在当前内存不等于已销毁：保留已持久化的 child UUID，防重复部署。
            if (RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent) != null) {
                return DeployResult.ALREADY_DEPLOYED;
            }
        }

        ResourceLocation childVehicleId = config.vehicleId();
        BaseVehicleData<?> childData = CommonAssetsManager.vehicleDataManager().getVehicleData(childVehicleId).orElse(null);
        if (childData == null) {
            return DeployResult.INVALID_TEMPLATE;
        }

        float spawnYaw = resolveSpawnYaw(parent, operator, config);
        Vec3 spawnPos = resolveSpawnPos(parent, config.spawnOffset(), spawnYaw);
        AbstractVehicle child = childData.construct(serverLevel, spawnPos, 0.0f, spawnYaw);
        if (child == null) {
            return DeployResult.SPAWN_FAILED;
        }
        if (!serverLevel.addFreshEntity(child)) {
            return DeployResult.SPAWN_FAILED;
        }
        configureLink(parent, child, operator, config);
        String optionId = RVP_DeployableUavCatalog.getSelectedOptionId(serverLevel, parent.getUUID(),
                parent.getVehicleId());
        RVP_LinkedUavStateTable.setDeploymentSnapshot(child, optionId, config);
        if (!RVP_DeployableUavStateSavedData.recordDeployment(serverLevel, parent.getUUID(), child.getUUID(),
                optionId, config.redeployCooldownTick())) {
            child.discard();
            return DeployResult.ALREADY_DEPLOYED;
        }
        applyInitialSpeed(child, spawnYaw, config.initialSpeed());
        applyTakeoffBehavior(child, parent);
        return DeployResult.SUCCESS;
    }

    public static boolean switchToLinkedUav(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle parent)) {
            return false;
        }
        if (!isDeployableControlOperator(parent, player)) {
            return false;
        }
        AbstractVehicle child = getLinkedChild(parent).orElse(null);
        if (child == null || child.isRemoved() || !child.isAlive() || child.isDestroyed()) {
            return false;
        }
        // 信号范围守卫（2026-10-06）：失联的无人机不可切入驾驶
        if (isUavOutOfSignalRange(child)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.ywzj_rvp.uav.signal_lost"), true);
            return false;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavControlSwitchAllowed(child)) {
            return false;
        }
        int returnSeatIndex = findOccupiedSeatIndex(parent, player);
        if (returnSeatIndex < 0) {
            return false;
        }
        // 更新放飞座位与母车旁回传锚点快照；之后即使母车实体卸载也无需猜测位置。
        if (parent.getMainCubeOBB() != null) {
            Vec3 anchor = parent.relativeRotPos(parent.position().add(
                    parent.getMainCubeOBB().obb().extents().x + 1.0, 1.0, 0.0), false);
            RVP_LinkedUavStateTable.setReturnAnchor(child, parent.level().dimension().location(), anchor);
        }
        boolean riding = player.startRiding(child);
        if (!riding) {
            return false;
        }
        RVP_LinkedUavStateTable.setReturnSeatIndex(child, returnSeatIndex);
        if (!child.seats.isEmpty() && child.seats.get(0).passengerId != player.getId()) {
            child.changeSeat(player, 0);
        }
        // 锁定母车上玩家离开前的座位（通常为驾驶位），防止他人占用/开走母车。
        // 玩家切回母车或自动上车成功时由 clearSeatLock 解除。
        // 锁状态存于静态注册表（不在实体上新增接口方法，避免 mixin 接口注入风险）。
        setSeatLock(parent, returnSeatIndex, player.getId());
        // 成功入座后才释放自动悬停，避免失败切换影响子机姿态；固定翼盘旋保持原逻辑。
        RVP_DeployableUavHoverService.tick(child);
        // 玩家进入无人机后盘旋继续（运动输入由 ControlUnitMixin 屏蔽）；
        // 玩家可按 F 键手动切换盘旋开/关
        return true;
    }

    public static boolean switchBackToParent(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle child)) {
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV] 切回失败：玩家不在载具上 vehicle={}", player.getVehicle());
            }
            return false;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(child)) {
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV] 切回失败：{} 非可部署UAV实例", child.getVehicleId());
            }
            return false;
        }
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        AbstractVehicle parent = resolveVehicleByUuid(child.level(), parentUuid);
        if (parent == null || parent.isRemoved() || !parent.isAlive() || parent.isDestroyed()) {
            // 母车实体暂不可用（区块卸载/已被移除）：直接下车，由 handleDismount 回传母车旁并
            // 进入延迟自动上车队列（母车区块重载后自动骑乘），避免玩家卡在无人机上。
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV] 母车暂不可用，下车回传: uav={} parentUuid={} parent={} removed={} alive={}",
                    child.getVehicleId(),
                    parentUuid,
                    parent == null ? "null" : parent.getVehicleId(),
                    parent != null && parent.isRemoved(),
                    parent != null && parent.isAlive());
            }
            if (player.getVehicle() == child) {
                player.stopRiding();
            }
            // 母车暂卸载时也先稳定无人旋翼机，回传事务继续由原队列执行。
            RVP_DeployableUavHoverService.tick(child);
            return true;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavControlSwitchAllowed(child)) {
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV] 切回失败：控制切换被禁用 child={}", child.getVehicleId());
            }
            return false;
        }
        if (parent.level() != player.level()) {
            // 跨维 stopRiding/teleport/startRiding 由 dismount 请求队列在关系清空后分两 tick 完成。
            player.stopRiding();
            RVP_DeployableUavHoverService.tick(child);
            return true;
        }
        boolean riding = player.startRiding(parent);
        if (!riding) {
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV] 切回失败：startRiding返回false parent={}", parent.getVehicleId());
            }
            return false;
        }
        int returnSeatIndex = RVP_LinkedUavStateTable.getReturnSeatIndex(child);
        if (returnSeatIndex >= 0 && returnSeatIndex < parent.seats.size()) {
            AbstractVehicle.Seat seat = parent.seats.get(returnSeatIndex);
            if (seat.passengerId == -1 && parent.getOwnOperatorUnit(player) != seat.partUnit) {
                parent.changeSeat(player, returnSeatIndex);
            }
        }
        // 清理母车座位锁：tryAutoRideParent 在 dismount 事件期间改为延迟到下一 tick 自动上车，
        // 该延迟任务会因玩家已骑乘（player.getVehicle()!=null）被丢弃，restoreSeatAndUnlock
        // 不再执行，因此这里必须显式解锁，避免座位锁残留导致他人无法占用母车。
        clearSeatLock(parent, player);
        // 调用旋翼无人值守服务：完成回乘后进入悬停；地面子车不接管飞控。
        RVP_DeployableUavHoverService.tick(child);
        // 切回母车仅对固定翼自动激活盘旋。
        RVP_DeployableUavConfig uavConfig = resolveInstanceConfig(child, parent);
        if (RVP_UavLoiterManager.supportsLoiter(child)
                && uavConfig.isConfigured() && uavConfig.autoLoiterOnSwitchBack()) {
            // 盘旋参数从通用 LoiterConfig 读取（优先母车，其次子载具自身）
            RVP_LoiterConfig loiterConfig = RVP_LoiterConfigCache.get(parent.getVehicleId());
            if (!loiterConfig.isConfigured()) {
                loiterConfig = RVP_LoiterConfigCache.get(child.getVehicleId());
            }
            if (loiterConfig.isConfigured()) {
                RVP_UavLoiterManager.enableFollowParent(
                        child.getUUID(),
                        parent.getUUID(),
                        loiterConfig.loiterRadius(),
                        loiterConfig.loiterAltitudeOffset(),
                        parent.getX(), parent.getY(), parent.getZ()
                );
            }
        }
        return true;
    }

    public static Optional<AbstractVehicle> getLinkedChild(AbstractVehicle parent) {
        UUID uuid = RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent);
        if (uuid == null) {
            uuid = RVP_DeployableUavLinkRegistry.getChildUuid(parent.getUUID());
        }
        return Optional.ofNullable(resolveVehicleByUuid(parent.level(), uuid));
    }

    public static Optional<AbstractVehicle> getLinkedParent(AbstractVehicle child) {
        UUID uuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        if (uuid == null) {
            uuid = RVP_DeployableUavLinkRegistry.getParentUuid(child.getUUID());
        }
        return Optional.ofNullable(resolveVehicleByUuid(child.level(), uuid));
    }

    public static void clearLinkedChild(AbstractVehicle parent) {
        RVP_LinkedUavStateTable.setLinkedChildVehicleUuid(parent, null);
        RVP_DeployableUavLinkRegistry.clearByParent(parent.getUUID());
    }

    public static int getRedeployCooldownRemainingTick(AbstractVehicle parent) {
        if (parent == null || !(parent.level() instanceof ServerLevel serverLevel)) {
            return 0;
        }
        return RVP_DeployableUavCooldownRegistry.getRemainingTick(serverLevel, parent.getUUID());
    }

    public static void handleDeployableUavRemoved(AbstractVehicle child) {
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(child)) {
            return;
        }
        // 某些永久移除入口先发 LeaveLevel 后解除乘客关系；必须先登记，不能等身份被清掉。
        java.util.List<Entity> passengers = new java.util.ArrayList<>();
        collectPassengerTree(child, passengers);
        for (Entity passenger : passengers) {
            if (passenger instanceof ServerPlayer player)
                tryAutoRideParent(player, child, null, null);
            passenger.stopRiding();
        }
        // 调用本项目回传协调器：如果尚有真人未到达锚点，保留链接、座位和租约供服务端重试。
        flushReturnsBeforeRemoval(child);
        UUID childUuid = child.getUUID();
        boolean returnPending = PENDING_AUTO_RIDE.values().stream()
                .anyMatch(pending -> pending.childUuid().equals(childUuid) && !pending.returned());
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        if (returnPending) {
            if (parentUuid != null && child.level() instanceof ServerLevel serverLevel) {
                commitTermination(serverLevel, child);
            }
            return;
        }
        if (child.level() instanceof ServerLevel serverLevel
                && parentUuid != null) {
            commitTermination(serverLevel, child);
            finishTermination(serverLevel, child);
        }
    }

    // ===== 信号范围（2026-10-06 新增）：母车与无人机超出 deployable_uav_signal_range 后失联并回收 =====

    /** 信号范围失联计数（key = UAV UUID）：持续超出信号范围的 tick 数。 */
    private static final Map<UUID, Integer> OUT_OF_SIGNAL_RANGE_TICKS = new HashMap<>();

    /** 持续超出信号范围该 tick 数后回收（3 秒防抖，防边界抖动误回收）。 */
    private static final int SIGNAL_LOST_RECALL_TICKS = 60;

    /**
     * 判断 UAV 是否已超出母车信号范围（水平距离）。
     * <p>配置 ≤0 = 无限；母车实体暂不可用（卸载/换维度中）不判超范围——失联回收只针对
     * "母车在场但距离过远"；无部署配置的载具（AC130 自身盘旋等）恒在范围内。</p>
     */
    public static boolean isUavOutOfSignalRange(AbstractVehicle uav) {
        if (uav == null || !RVP_LinkedUavStateTable.isDeployableUavInstance(uav)) {
            return false;
        }
        if (uav.isDestroyed()) {
            return true;
        }
        AbstractVehicle parent = resolveVehicleByUuid(uav.level(), RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav));
        if (parent == null) {
            return false;
        }
        if (parent.level() != uav.level()) return true;
        RVP_DeployableUavConfig config = resolveInstanceConfig(uav, parent);
        if (!config.isConfigured()) {
            return false;
        }
        double range = config.effectiveSignalRange();
        double dx = uav.getX() - parent.getX();
        double dz = uav.getZ() - parent.getZ();
        return dx * dx + dz * dz > range * range;
    }

    /** 已部署实例固定使用放飞时的配置快照；老存量缺快照时再回退母车当前配置。 */
    private static RVP_DeployableUavConfig resolveInstanceConfig(AbstractVehicle child, AbstractVehicle parent) {
        RVP_DeployableUavConfig snapshot = RVP_LinkedUavStateTable.getDeploymentConfig(child);
        if (snapshot != null) {
            return snapshot;
        }
        if (parent != null && parent.level() instanceof ServerLevel serverLevel) {
            return RVP_DeployableUavCatalog.getEffectiveConfig(serverLevel, parent);
        }
        return RVP_DeployableUavConfig.DISABLED;
    }

    /**
     * 每 tick 信号范围检查（{@code RVP_LinkedUavEventHandler.tickVehicles} 对部署实例调用）：
     * 超范围瞬间失联（盘旋关闭+清杆，M 切出/F 盘旋/地图标点被 guard 拒收）；
     * 持续 {@link #SIGNAL_LOST_RECALL_TICKS} 后回收——机上玩家踢回母车（走 dismount 回传链）、
     * UAV discard、清链接并进入重部署冷却。
     */
    public static void checkSignalRange(AbstractVehicle uav) {
        if (uav == null || uav.isDestroyed() || uav.isRemoved()) return;
        UUID uavUuid = uav.getUUID();
        if (!isUavOutOfSignalRange(uav)) {
            OUT_OF_SIGNAL_RANGE_TICKS.remove(uavUuid);
            RVP_UavLoiterManager.resumeAfterSignal(uavUuid);
            RVP_UavLoiterManager.persist(uav);
            return;
        }
        int ticks = OUT_OF_SIGNAL_RANGE_TICKS.merge(uavUuid, 1, Integer::sum);
        if (ticks == 1) {
            // 失联瞬间：关盘旋 + 清 controlUnit（防最后一次制导的油门/航向锁存直飞）
            RVP_UavLoiterManager.pauseForSignal(uavUuid);
            RVP_UavLoiterManager.persist(uav);
            uav.controlUnit.reset();
            messageEntity(uav.getDriver(), "message.ywzj_rvp.uav.signal_lost");
            notifyParentOperator(uav, "message.ywzj_rvp.uav.signal_lost");
        }
        if (ticks < SIGNAL_LOST_RECALL_TICKS) {
            return;
        }
        OUT_OF_SIGNAL_RANGE_TICKS.remove(uavUuid);
        recallUav(uav);
    }

    /** 回收：机上乘客 stopRiding（触发 dismount 回传母车链）→ discard UAV → 清链 + 进重部署冷却。 */
    private static void recallUav(AbstractVehicle uav) {
        AbstractVehicle parent = resolveVehicleByUuid(uav.level(), RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav));
        // 先拷贝乘客列表再逐个踢下：stopRiding 过程会变更乘客集合
        for (Entity passenger : new java.util.ArrayList<>(uav.getPassengers())) {
            passenger.stopRiding();
        }
        uav.discard();
        handleDeployableUavRemoved(uav);
        notifyParentOperator(parent, "message.ywzj_rvp.uav.signal_recalled");
    }

    /** 给母车操作者发 actionbar 提示（非玩家操作者静默）。 */
    private static void notifyParentOperator(AbstractVehicle uavOrParent, String key) {
        AbstractVehicle parent = uavOrParent;
        if (uavOrParent != null && RVP_LinkedUavStateTable.isDeployableUavInstance(uavOrParent)) {
            parent = resolveVehicleByUuid(uavOrParent.level(), RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uavOrParent));
        }
        if (parent != null) {
            messageEntity(parent.getDriver(), key);
        }
    }

    private static void messageEntity(@Nullable LivingEntity entity, String key) {
        if (entity instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
        }
    }

    private static void configureLink(AbstractVehicle parent, AbstractVehicle child, @Nullable LivingEntity operator, RVP_DeployableUavConfig config) {
        RVP_LinkedUavStateTable.setLinkedChildVehicleUuid(parent, child.getUUID());
        RVP_DeployableUavLinkRegistry.link(parent.getUUID(), child.getUUID());
        RVP_LinkedUavStateTable.setDeployableUavInstance(child, true);
        RVP_LinkedUavStateTable.setLinkedParentVehicleUuid(child, parent.getUUID());
        RVP_LinkedUavStateTable.setLinkedParentDimension(child, parent.level().dimension().location());
        RVP_LinkedUavStateTable.setLinkedLauncherVehicleUuid(child, config.autoLinkDatalink() ? parent.getUUID() : null);
        RVP_LinkedUavStateTable.setReturnSeatIndex(child, findSeatIndex(parent, operator));
        RVP_LinkedUavStateTable.setDeployableUavRole(child, config.role());
        RVP_LinkedUavStateTable.setDatalinkRole(child, config.autoLinkDatalink() ? config.role() : "none");
        RVP_LinkedUavStateTable.setDeployableUavControlSwitchAllowed(child, config.allowControlSwitch());
        // 部署时先持久化母车旁回传锚点；母车卸载后真实玩家仍有确定的维度与位置可回传。
        if (parent.getMainCubeOBB() != null) {
            Vec3 anchor = parent.relativeRotPos(parent.position().add(
                    parent.getMainCubeOBB().obb().extents().x + 1.0, 1.0, 0.0), false);
            RVP_LinkedUavStateTable.setReturnAnchor(child, parent.level().dimension().location(), anchor);
        } else {
            RVP_LinkedUavStateTable.setReturnAnchor(child, parent.level().dimension().location(), parent.position().add(2, 1, 0));
        }
    }

    private static int findSeatIndex(AbstractVehicle vehicle, @Nullable LivingEntity operator) {
        if (operator == null) {
            return 0;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId != null && seat.passengerId == operator.getId()) {
                return seat.seatIndex;
            }
        }
        return 0;
    }

    /** 当前母车操作者必须实际占据配置允许的座位，不能用默认座位掩盖未找到座位的情况。 */
    public static boolean isDeployableControlOperator(AbstractVehicle parent, ServerPlayer player) {
        if (parent == null || player == null || player.getVehicle() != parent) {
            return false;
        }
        int seatIndex = findOccupiedSeatIndex(parent, player);
        AbstractVehicle activeChild = getLinkedChild(parent).orElse(null);
        RVP_DeployableUavConfig config = activeChild != null ? resolveInstanceConfig(activeChild, parent)
                : parent.level() instanceof ServerLevel serverLevel
                ? RVP_DeployableUavCatalog.getEffectiveConfig(serverLevel, parent)
                : RVP_DeployableUavConfig.DISABLED;
        return config.isConfigured() && isDeployableControlSeatAllowed(config, seatIndex);
    }

    /** 盤旋命令可由实际驾驶载具的操作者使用；母车向子机下发时还须满足母车部署座位权限。 */
    public static boolean isLoiterControlOperator(ServerPlayer player, AbstractVehicle commandVehicle,
                                                  AbstractVehicle targetUav) {
        if (player == null || commandVehicle == null || targetUav == null
                || player.getVehicle() != commandVehicle) {
            return false;
        }
        if (commandVehicle != targetUav) {
            return isDeployableControlOperator(commandVehicle, player);
        }
        return commandVehicle.getDriver() == player || commandVehicle.controlUnit.getOperator() == player;
    }

    /** 纯座位策略：索引必须真实存在；空配置只允许驾驶位0。 */
    public static boolean isDeployableControlSeatAllowed(RVP_DeployableUavConfig config, int seatIndex) {
        return config != null && seatIndex >= 0 && config.isSeatAllowed(seatIndex);
    }

    private static int findOccupiedSeatIndex(AbstractVehicle vehicle, ServerPlayer player) {
        if (vehicle == null || player == null) {
            return -1;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId != null && seat.passengerId == player.getId()) {
                return seat.seatIndex;
            }
        }
        return -1;
    }

    private static float resolveSpawnYaw(AbstractVehicle parent, @Nullable LivingEntity operator, RVP_DeployableUavConfig config) {
        return switch (config.spawnYawMode().toLowerCase()) {
            case "operator_look" -> operator != null ? operator.getYRot() : parent.getYRot();
            default -> parent.getYRot();
        };
    }

    private static Vec3 resolveSpawnPos(AbstractVehicle parent, Vec3 offset, float yaw) {
        double radians = Math.toRadians(-yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Vec3 rotated = new Vec3(
                offset.x * cos - offset.z * sin,
                offset.y,
                offset.x * sin + offset.z * cos
        );
        Vec3 basePos = parent.position().add(rotated);
        return new Vec3(basePos.x, basePos.y + 0.1, basePos.z);
    }

    /**
     * 释放子载具后沿生成朝向赋予水平初速度。
     *
     * @param child        生成的子载具
     * @param spawnYaw     生成朝向（度）
     * @param initialSpeed 初速度大小（blocks/tick）；≤0 时不赋予
     */
    private static void applyInitialSpeed(AbstractVehicle child, float spawnYaw, float initialSpeed) {
        if (initialSpeed <= 0f) {
            return;
        }
        Vec3 forward = Vec3.directionFromRotation(0f, spawnYaw).scale(initialSpeed);
        child.setDeltaMovement(forward);
    }

    /**
     * 部署旋翼机启动悬停；固定翼按 LoiterConfig 起飞并盘旋；地面子车不接管飞控。
     */
    private static void applyTakeoffBehavior(AbstractVehicle child, AbstractVehicle parent) {
        // 新部署辅助载具立即回满燃油和弹药（不依赖本体无限燃料/RVP 生成补给开关）
        RVP_DeployableSupplyService.initialize(child);
        if (child instanceof RotaryWingVehicle rotaryWing) {
            // addFreshEntity 已完成本体 initData，按当前质量/旋翼参数初始化部署悬停。
            RVP_DeployableUavHoverService.initializeDeployment(rotaryWing);
            return;
        }
        if (!RVP_UavLoiterManager.supportsLoiter(child)) return;
        RVP_LoiterConfig loiterConfig = RVP_LoiterConfigCache.get(child.getVehicleId());
        if (!loiterConfig.isConfigured()) {
            return;
        }
        if (loiterConfig.autoLoiterOnTakeoff()) {
            RVP_UavLoiterManager.enableFollowParent(
                    child.getUUID(),
                    parent.getUUID(),
                    loiterConfig.loiterRadius(),
                    loiterConfig.loiterAltitudeOffset(),
                    parent.getX(), parent.getY(), parent.getZ()
            );
            if (child instanceof FixedWingVehicle fw) {
                fw.toggleEngine(true);
                fw.setPower(100f);
                fw.setThrottleLevel(100f);
            }
        }
    }

    private static AbstractVehicle resolveVehicleByUuid(net.minecraft.world.level.Level level, UUID uuid) {
        if (uuid == null || level.getServer() == null) {
            return null;
        }
        for (ServerLevel serverLevel : level.getServer().getAllLevels()) {
            Entity entity = serverLevel.getEntity(uuid);
            if (entity instanceof AbstractVehicle vehicle) return vehicle;
        }
        return null;
    }

    // ==================== 自动上车 + 母车座位锁 ====================

    /**
     * 玩家离开无人机后仅登记返回请求；Forge dismount 回调发生在 passenger 关系清空前，
     * 因此跨维传送和 startRiding 均交给后续服务器 tick 执行，避免事件递归。
     */
    public static void tryAutoRideParent(ServerPlayer player, AbstractVehicle uav,
                                         @Nullable ResourceLocation returnDimension,
                                         @Nullable Vec3 returnPosition) {
        if (player == null || uav == null || player.level().isClientSide()) {
            return;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(uav)) {
            return;
        }
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav);
        if (parentUuid == null) {
            return;
        }
        int seatIndex = Math.max(0, RVP_LinkedUavStateTable.getReturnSeatIndex(uav));
        scheduleAutoRide(player, uav, parentUuid, seatIndex, returnDimension, returnPosition);
    }

    private static void restoreSeatAndUnlock(ServerPlayer player, AbstractVehicle parent, int seatIndex) {
        if (seatIndex < parent.seats.size()) {
            AbstractVehicle.Seat seat = parent.seats.get(seatIndex);
            if (seat.passengerId == -1) {
                parent.changeSeat(player, seatIndex);
            }
        }
        clearSeatLock(parent, player);
    }

    /** 清除母车上属于该玩家的座位锁（玩家已回到母车）。 */
    public static void clearSeatLock(AbstractVehicle parent, ServerPlayer owner) {
        SeatLockInfo lock = PARENT_SEAT_LOCKS.get(parent.getUUID());
        if (lock != null && (lock.ownerPlayerUuid() != null
                ? lock.ownerPlayerUuid().equals(owner.getUUID()) : lock.ownerPlayerId() == owner.getId())) {
            PARENT_SEAT_LOCKS.remove(parent.getUUID());
            parent.getPersistentData().remove("rvpUavSeatLock");
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV-LOCK] 解锁母车 {} seat={} owner={}",
                    parent.getVehicleId(), lock.seatIndex(), lock.ownerPlayerId());
            }
        }
    }

    /** 设置母车座位锁（玩家切至无人机期间，防止他人占用/开走母车）。 */
    public static void setSeatLock(AbstractVehicle parent, int seatIndex, int ownerPlayerId) {
        UUID ownerUuid = parent != null && parent.level() instanceof ServerLevel serverLevel
                && serverLevel.getEntity(ownerPlayerId) instanceof ServerPlayer player
                ? player.getUUID() : null;
        setSeatLock(parent, seatIndex, ownerPlayerId, ownerUuid);
    }

    /** 设置带明确玩家 UUID 的母车座位锁，支持玩家已离开母车或跨维重载恢复。 */
    private static void setSeatLock(AbstractVehicle parent, int seatIndex, int ownerPlayerId, @Nullable UUID ownerUuid) {
        if (parent == null || seatIndex < 0) {
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV-LOCK] setSeatLock 跳过 parent={} seatIndex={}",
                    parent == null ? "null" : parent.getVehicleId(), seatIndex);
            }
            return;
        }
        PARENT_SEAT_LOCKS.put(parent.getUUID(), new SeatLockInfo(seatIndex, ownerPlayerId, ownerUuid));
                if (RVP_DebugFlags.UAV.isEnabled()) {
            LOGGER.info("[RVP-UAV-LOCK] 锁母车 {} seat={} owner={}",
                parent.getVehicleId(), seatIndex, ownerPlayerId);
        }
    }

    /** 无人机载具每 tick 检查正在驾驶的玩家；服务重启后只重建缺失的临时母车锁。 */
    public static void restoreActivePilotSeatLock(AbstractVehicle child) {
        if (child == null || !RVP_LinkedUavStateTable.isDeployableUavInstance(child)
                || !(child.getDriver() instanceof ServerPlayer player)
                || player.getVehicle() != child) {
            return;
        }
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        AbstractVehicle parent = resolveVehicleByUuid(child.level(), parentUuid);
        if (parent == null || parent.isRemoved() || !parent.isAlive() || parent.isDestroyed()
                || PARENT_SEAT_LOCKS.containsKey(parent.getUUID())) {
            return;
        }
        int returnSeat = Math.max(0, RVP_LinkedUavStateTable.getReturnSeatIndex(child));
        if (returnSeat >= parent.seats.size()) {
            return;
        }
        setSeatLock(parent, returnSeat, player.getId(), player.getUUID());
    }

    /**
     * 玩家离开母车进入无人机后锁定母车座位（防止他人占用/开走母车）。
     * 优先取玩家在母车上的实际座位，找不到时用部署时记录的返回座位兜底。
     */
    public static void lockParentSeat(AbstractVehicle parent, @Nullable ServerPlayer operator, @Nullable AbstractVehicle uav) {
        if (parent == null || operator == null) {
            return;
        }
        int seatIndex = findSeatIndex(parent, operator);
        if (seatIndex < 0 && uav != null) {
            seatIndex = RVP_LinkedUavStateTable.getReturnSeatIndex(uav);
        }
        if (seatIndex < 0) {
            seatIndex = 0;
        }
        setSeatLock(parent, seatIndex, operator.getId());
    }

    /**
     * 母车座位锁是否应拒绝该乘客上车。
     * <p>无人机在飞、锁未解除期间，非锁持有者一律禁止登上母车任意座位（防止他人占用驾驶位把母车开走）。</p>
     */
    public static boolean shouldRejectMount(AbstractVehicle vehicle, @Nullable LivingEntity passenger) {
        if (vehicle == null) {
            return false;
        }
        SeatLockInfo lock = PARENT_SEAT_LOCKS.get(vehicle.getUUID());
        if (lock == null) {
            return false;
        }
        boolean reject = passenger == null || (lock.ownerPlayerUuid() != null
                ? !passenger.getUUID().equals(lock.ownerPlayerUuid())
                : passenger.getId() != lock.ownerPlayerId());
        if (reject) {
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV-LOCK] 拒绝 {} 上母车 {}（锁 owner={} seat={}）",
                    passenger == null ? "null" : passenger.getName().getString(),
                    vehicle.getVehicleId(), lock.ownerPlayerId(), lock.seatIndex());
            }
        }
        return reject;
    }

    /** 读取母车座位锁；无锁返回 null。 */
    @Nullable
    public static SeatLockInfo getSeatLock(AbstractVehicle parent) {
        if (parent == null) {
            return null;
        }
        return PARENT_SEAT_LOCKS.get(parent.getUUID());
    }

    /** 母车永久移除时清理其座位锁；暂时卸载保留静态会话锁。 */
    public static void cleanupSeatLock(AbstractVehicle parent) {
        if (parent != null) {
            PARENT_SEAT_LOCKS.remove(parent.getUUID());
        }
    }

    /** 玩家重生/离开时解锁其锁定的所有母车座位（防止座位被永久锁死）。 */
    public static void unlockSeatForPlayer(Level level, int playerId) {
        UUID playerUuid = level.getEntity(playerId) == null ? null : level.getEntity(playerId).getUUID();
        releaseSeatLockForPlayer(playerId, playerUuid, level.getServer());
    }

    private static void releaseSeatLockForPlayer(int playerId, UUID playerUuid, MinecraftServer server) {
        PARENT_SEAT_LOCKS.entrySet().removeIf(entry -> {
            SeatLockInfo lock = entry.getValue();
            boolean match = lock.ownerPlayerUuid() != null
                    ? lock.ownerPlayerUuid().equals(playerUuid)
                    : playerId >= 0 && lock.ownerPlayerId() == playerId;
            if (match) {
                for (ServerLevel serverLevel : server.getAllLevels()) {
                    Entity entity = serverLevel.getEntity(entry.getKey());
                    if (entity instanceof AbstractVehicle parent) parent.getPersistentData().remove("rvpUavSeatLock");
                }
            }
            return match;
        });
    }

    /** 服务端每 tick 强制母车座位锁（替代被删 mixin 的 onEnterVehicle/changeSeat 注入）： */
    public static void enforceSeatLocks(ServerLevel serverLevel) {
        if (PARENT_SEAT_LOCKS.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<UUID, SeatLockInfo> entry : PARENT_SEAT_LOCKS.entrySet()) {
            SeatLockInfo lock = entry.getValue();
            // 注意：ServerLevel.getEntity 只返回本维度内的实体。母车不在本维度时直接跳过，
            // 绝不能当 stale 清理——否则多维度世界（overworld/nether/end 每个维度都会执行本方法）
            // 会在母车所在维度之外的维度把锁误删，导致"锁刚设置就被清除"。
            // 母车永久移除时由 RVP_LinkedUavEventHandler 清理；暂时卸载保留 UUID 锁等待母车重载。
            if (!(serverLevel.getEntity(entry.getKey()) instanceof AbstractVehicle parent)) {
                continue;
            }
            Optional<AbstractVehicle.Seat> lockedSeat = parent.seats.stream()
                    .filter(seat -> seat.seatIndex == lock.seatIndex())
                    .findFirst();
            if (lockedSeat.isEmpty() || lockedSeat.get().passengerId == -1) {
                continue;
            }
            if (!(parent.level().getEntity(lockedSeat.get().passengerId) instanceof ServerPlayer serverPlayer)) {
                continue;
            }
            if (lock.ownerPlayerUuid() != null
                    ? lock.ownerPlayerUuid().equals(serverPlayer.getUUID())
                    : lock.ownerPlayerId() >= 0 && lock.ownerPlayerId() == serverPlayer.getId()) {
                continue; // 持有者 UUID 匹配，避免重连后 entityId 改变而踢出真正操作者
            }
            // 直接踢出而非换座：母车锁定的语义是"锁住母车"，入侵者不应留在母车任意座位上
            // （配合 onMount 上车事件拦截，此处的每 tick 强制定位为兜底）。
                        if (RVP_DebugFlags.UAV.isEnabled()) {
                LOGGER.info("[RVP-UAV-LOCK] 每tick兜底踢出 {}（母车 {} 锁 seat={} owner={}）",
                    serverPlayer.getName().getString(), parent.getVehicleId(),
                    lock.seatIndex(), lock.ownerPlayerId());
            }
            serverPlayer.stopRiding();
        }
    }

    /** 记录母车（父车）最新位置快照（key = 无人机实体 UUID）；position 为 null 时清除。 */
    public static void setLinkedParentLastPosition(UUID uavUuid, @Nullable Vec3 position) {
        if (uavUuid == null) {
            return;
        }
        if (position == null) {
            PARENT_LAST_POSITIONS.remove(uavUuid);
        } else {
            PARENT_LAST_POSITIONS.put(uavUuid, position);
        }
    }

    /** 读取母车（父车）位置快照；无记录返回 null。 */
    @Nullable
    public static Vec3 getLinkedParentLastPosition(UUID uavUuid) {
        if (uavUuid == null) {
            return null;
        }
        return PARENT_LAST_POSITIONS.get(uavUuid);
    }

    /** 无人机实体移除时清理其母车位置快照。 */
    public static void clearLinkedParentLastPosition(UUID uavUuid) {
        if (uavUuid != null) {
            PARENT_LAST_POSITIONS.remove(uavUuid);
        }
    }

    private static void scheduleAutoRide(ServerPlayer player, AbstractVehicle uav, UUID parentUuid, int seatIndex,
                                         @Nullable ResourceLocation returnDimension,
                                         @Nullable Vec3 returnPosition) {
        if (player == null || uav == null || parentUuid == null) {
            return;
        }
        PendingAutoRide existing = PENDING_AUTO_RIDE.get(player.getUUID());
        if (existing != null && existing.childUuid().equals(uav.getUUID()) && !existing.returned()) return;
        ReturnTarget target = resolveReturnTarget(uav);
        if (target != null) {
            returnDimension = target.level().dimension().location();
            returnPosition = target.position();
        }
        ResourceLocation parentDimension = RVP_LinkedUavStateTable.getLinkedParentDimension(uav);
        if (parentDimension == null) parentDimension = player.level().dimension().location();
        PendingAutoRide pending = new PendingAutoRide(uav.getUUID(), parentUuid, parentDimension,
                player.level().dimension().location(), seatIndex, AUTO_RIDE_MAX_RETRY,
                player.server.getTickCount() + 1L, returnDimension, returnPosition, false);
        PENDING_AUTO_RIDE.put(player.getUUID(), pending);
        // 先持久化，再允许载具进入终态；掉线不意味着回传已完成。
        persistReturn(player.serverLevel(), player.getUUID(), pending);
    }

    /** 所有返回入口共用同一目的地，避免自爆检查的是放飞点而实际返回移动中的母车。 */
    public record ReturnTarget(ServerLevel level, Vec3 position) {}

    @Nullable public static ReturnTarget resolveReturnTarget(AbstractVehicle child) {
        AbstractVehicle parent = resolveVehicleByUuid(child.level(), RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child));
        if (parent != null && !parent.isRemoved() && parent.isAlive() && !parent.isDestroyed()
                && parent.level() instanceof ServerLevel parentLevel) {
            double side = parent.getMainCubeOBB() == null ? 2.0 : parent.getMainCubeOBB().obb().extents().x + 1;
            return new ReturnTarget(parentLevel, parent.relativeRotPos(parent.position().add(side, 1, 0), false));
        }
        ResourceLocation dim = RVP_LinkedUavStateTable.getReturnAnchorDimension(child);
        Vec3 pos = RVP_LinkedUavStateTable.getReturnAnchorPosition(child);
        if (pos == null) {
            dim = RVP_LinkedUavStateTable.getLinkedParentDimension(child);
            pos = PARENT_LAST_POSITIONS.get(child.getUUID());
            if (pos != null) pos = pos.add(0, 1, 0);
            else pos = RVP_LinkedUavStateTable.getFakeOperatorPosition(child);
        }
        ServerLevel targetLevel = findServerLevel(child.level().getServer(), dim);
        return targetLevel == null || pos == null ? null : new ReturnTarget(targetLevel, pos);
    }

    private static void persistReturn(ServerLevel level, UUID player, PendingAutoRide pending) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putUUID("child", pending.childUuid());
        tag.putUUID("parent", pending.parentUuid());
        tag.putString("parentDimension", pending.parentDimension().toString());
        tag.putString("playerDimension", pending.playerDimension().toString());
        tag.putInt("seat", pending.seatIndex());
        if (pending.returnDimension() != null) tag.putString("dimension", pending.returnDimension().toString());
        if (pending.returnPosition() != null) {
            tag.putDouble("x", pending.returnPosition().x);
            tag.putDouble("y", pending.returnPosition().y);
            tag.putDouble("z", pending.returnPosition().z);
        }
        RVP_DeployableUavStateSavedData.get(level).putPendingReturn(player, tag);
    }

    /** 关系已解除后才能传送；成功必须回读维度和坐标，不用超时替代成功。 */
    private static PendingAutoRide tryReturn(ServerPlayer player, PendingAutoRide pending) {
        AbstractVehicle parent = resolveVehicleByUuid(player.level(), pending.parentUuid());
        if (parent != null && player.getVehicle() == parent) {
            RVP_DeployableUavStateSavedData.get(player.serverLevel()).clearPendingReturn(player.getUUID());
            return confirmedReturn(player, pending);
        }
        if (player.getVehicle() != null && player.getVehicle().getUUID().equals(pending.childUuid())
                && RVP_DeployableUavStateSavedData.get(player.serverLevel()).isTerminating(pending.childUuid())) {
            // 重连可能恢复了已终结子机的骑乘 NBT；此处在普通 tick 栈中安全解除。
            player.stopRiding();
        }
        if (player.isPassenger()) return pending;
        ServerLevel targetLevel = findServerLevel(player.server, pending.returnDimension());
        Vec3 pos = pending.returnPosition();
        if (parent != null && !parent.isRemoved() && !parent.isDestroyed() && parent.isAlive()) {
            targetLevel = (ServerLevel) parent.level();
            double side = parent.getMainCubeOBB() == null ? 2.0 : parent.getMainCubeOBB().obb().extents().x + 1;
            pos = parent.relativeRotPos(parent.position().add(side, 1, 0), false);
        }
        if (targetLevel == null || pos == null) return pending;
        if (player.level() == targetLevel) player.teleportTo(pos.x, pos.y, pos.z);
        else player.teleportTo(targetLevel, pos.x, pos.y, pos.z, java.util.Set.of(), player.getYRot(), player.getXRot());
        if (player.level() != targetLevel || player.position().distanceToSqr(pos) > 0.25) return pending;
        player.fallDistance = 0;
        player.setDeltaMovement(Vec3.ZERO);
        RVP_DeployableUavStateSavedData.get(targetLevel).clearPendingReturn(player.getUUID());
        return confirmedReturn(player, pending);
    }

    private static PendingAutoRide confirmedReturn(ServerPlayer player, PendingAutoRide pending) {
        return new PendingAutoRide(pending.childUuid(), pending.parentUuid(), pending.parentDimension(),
                player.level().dimension().location(), pending.seatIndex(), AUTO_RIDE_MAX_RETRY,
                player.server.getTickCount() + 1L, null, null, true);
    }

    /**
     * 爆炸入口前完成已经因部署子机毁伤而触发的回传：EntityMountEvent 已返回后才调用，
     * 因此这里不会在 stopRiding 的重入回调栈内跨维传送。母车座位恢复仍由后续 tick 单独重试。
     */
    public static boolean flushDestroyedDeployableReturns(ServerLevel level) {
        return flushDeployableReturns(level, null, true);
    }

    /** 自然毁伤爆炸入口：先回传真乘员，并以窄范围查询补捉没有乘员的被毁部署child。 */
    public static boolean beforeNaturalVehicleExplosion(ServerLevel level, Vec3 center) {
        boolean returned = flushDestroyedDeployableReturns(level);
        net.minecraft.world.phys.AABB local = new net.minecraft.world.phys.AABB(
                center.x - 1.0, center.y - 1.0, center.z - 1.0,
                center.x + 1.0, center.y + 1.0, center.z + 1.0);
        for (AbstractVehicle child : level.getEntitiesOfClass(AbstractVehicle.class, local)) {
            if (!child.isDestroyed() || !RVP_LinkedUavStateTable.isDeployableUavInstance(child)
                    || child.position().distanceToSqr(center) > 2.25) continue;
            UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
            if (parentUuid == null) continue;
            RVP_DeployableUavStateSavedData.beginTermination(level, parentUuid, child.getUUID(),
                    PENDING_AUTO_RIDE.values().stream().anyMatch(p -> p.childUuid().equals(child.getUUID())
                            && !p.returned()) ? "returning" : "terminating");
            returned &= flushDeployableReturns(level, child, true);
        }
        return returned;
    }

    /** 删除前同步完成回传，避免非爆炸回收路径先清除锚点或部署租约。 */
    private static void flushReturnsBeforeRemoval(AbstractVehicle child) {
        if (child != null && child.level() instanceof ServerLevel serverLevel) {
            flushDeployableReturns(serverLevel, child, false);
        }
    }

    private static boolean flushDeployableReturns(ServerLevel level, @Nullable AbstractVehicle knownChild,
                                                  boolean destroyedOnly) {
        if (level == null || level.getServer() == null) {
            return false;
        }
        MinecraftServer server = level.getServer();
        java.util.Set<AbstractVehicle> children = new java.util.HashSet<>();
        if (knownChild != null && RVP_LinkedUavStateTable.isDeployableUavInstance(knownChild)
                && (!destroyedOnly || knownChild.isDestroyed())) children.add(knownChild);
        // 只按待回传 child UUID 查实体，不在每次 Explosion.Start 遍历整维度实体集合。
        for (PendingAutoRide pending : PENDING_AUTO_RIDE.values()) {
            Entity entity = knownChild != null && knownChild.getUUID().equals(pending.childUuid())
                    ? knownChild : level.getEntity(pending.childUuid());
            if (entity instanceof AbstractVehicle child && RVP_LinkedUavStateTable.isDeployableUavInstance(child)
                    && (!destroyedOnly || child.isDestroyed())) children.add(child);
        }
        for (AbstractVehicle child : children) {
            UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
            if (parentUuid != null) {
                if (child.isDestroyed()) {
                    String reason = PENDING_AUTO_RIDE.values().stream().anyMatch(p -> p.childUuid().equals(child.getUUID())
                            && !p.returned()) ? "returning" : "terminating";
                    commitTermination(level, child);
                }
            }
        }
        for (Map.Entry<UUID, PendingAutoRide> entry : PENDING_AUTO_RIDE.entrySet()) {
            PendingAutoRide pending = entry.getValue();
            Entity childEntity = knownChild != null && knownChild.getUUID().equals(pending.childUuid())
                    ? knownChild : level.getEntity(pending.childUuid());
            if (!(childEntity instanceof AbstractVehicle child) || (destroyedOnly && !child.isDestroyed())
                    || pending.returned()) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.isRemoved() || !player.isAlive()) {
                continue;
            }
            if (destroyedOnly) {
                // 仅当前child毁伤爆炸：玩家若尚未成功传送，下一段LivingHurtEvent仍会保护这一次伤害。
                RETURN_PROTECTION.put(player.getUUID(), new ReturnProtection(child.getUUID(), server.getTickCount()));
            }
            entry.setValue(tryReturn(player, pending));
        }
        return children.stream().allMatch(child -> PENDING_AUTO_RIDE.values().stream()
                .noneMatch(p -> p.childUuid().equals(child.getUUID()) && !p.returned()));
    }

    /** 自爆/自然毁伤终结共用的回传入口；只负责下车与传送，不触发爆炸或移除载具。 */
    public static boolean returnPassengersForTermination(ServerLevel level, AbstractVehicle child,
                                                          Vec3 blastCenter, float blastRadius) {
        if (level == null || child == null || child.level() != level
                || !RVP_LinkedUavStateTable.isDeployableUavInstance(child)) return false;
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        if (parentUuid == null) return false;
        if (wouldReturnAnchorBeHitByExplosion(level, child, blastCenter, blastRadius)) return false;
        java.util.List<Entity> passengers = new java.util.ArrayList<>();
        collectPassengerTree(child, passengers);
        for (Entity passenger : passengers) {
            if (passenger.isPassenger()) passenger.stopRiding();
        }
        ResourceLocation anchorDimension = RVP_LinkedUavStateTable.getReturnAnchorDimension(child);
        Vec3 anchorPosition = RVP_LinkedUavStateTable.getReturnAnchorPosition(child);
        int returnSeat = Math.max(0, RVP_LinkedUavStateTable.getReturnSeatIndex(child));
        for (Entity passenger : passengers) {
            if (passenger instanceof ServerPlayer player && !player.isRemoved() && player.isAlive()) {
                // 调用本项目返回队列，确保座位/UUID/放飞锚点完整；所有stopRiding回调已在上方返回。
                scheduleAutoRide(player, child, parentUuid, returnSeat, anchorDimension, anchorPosition);
            }
        }
        boolean returned = flushDeployableReturns(level, child, false);
        java.util.List<ServerPlayer> players = passengers.stream().filter(ServerPlayer.class::isInstance)
                .map(ServerPlayer.class::cast).toList();
        boolean allConfirmed = returned && !RVP_DeployableUavStateSavedData.get(level).hasPendingReturn(child.getUUID())
                && players.stream().allMatch(player -> {
            PendingAutoRide pending = PENDING_AUTO_RIDE.get(player.getUUID());
            return pending != null && pending.childUuid().equals(child.getUUID()) && pending.returned()
                    && player.level() instanceof ServerLevel
                    && (player.level() != level || player.position().distanceToSqr(blastCenter)
                        > (double) (blastRadius + 2) * (blastRadius + 2))
                    && (pending.returnDimension() == null
                    || player.level().dimension().location().equals(pending.returnDimension()));
        });
        if (allConfirmed) {
            long damageTick = level.getServer().getTickCount();
            for (ServerPlayer player : players) RETURN_PROTECTION.put(player.getUUID(),
                    new ReturnProtection(child.getUUID(), damageTick));
        }
        return allConfirmed;
    }

    /** 自爆前门禁：若任一乘员回传锚点会落入同维战斗部杀伤半径，拒绝该次自爆。 */
    public static boolean wouldReturnAnchorBeHitByExplosion(ServerLevel level, AbstractVehicle child,
                                                            Vec3 blastCenter, float blastRadius) {
        if (level == null || child == null || blastCenter == null || blastRadius < 0) return true;
        java.util.List<Entity> passengers = new java.util.ArrayList<>();
        collectPassengerTree(child, passengers);
        boolean hasPlayers = passengers.stream().anyMatch(ServerPlayer.class::isInstance)
                || PENDING_AUTO_RIDE.values().stream().anyMatch(p -> p.childUuid().equals(child.getUUID()) && !p.returned());
        if (!hasPlayers) return false;
        ReturnTarget target = resolveReturnTarget(child);
        return target == null || (target.level() == level
                && target.position().distanceToSqr(blastCenter) <= (double) (blastRadius + 2) * (blastRadius + 2));
    }

    /** 自爆所有乘员安全回传后、战斗部提交前开始终结锁与部署冷却。 */
    public static boolean commitTermination(ServerLevel level, AbstractVehicle child) {
        if (level == null || child == null || child.level() != level
                || !RVP_LinkedUavStateTable.isDeployableUavInstance(child)) return false;
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        if (parentUuid == null) return false;
        if (!RVP_DeployableUavStateSavedData.hasActiveDeployment(level, parentUuid)) {
            // 接管升级前已在飞的 linked child；只补租约，不重置已存在的部署记录。
            RVP_DeployableUavConfig config = resolveInstanceConfig(child, resolveVehicleByUuid(level, parentUuid));
            RVP_DeployableUavStateSavedData.get(level).recordDeployment(parentUuid, child.getUUID(),
                    RVP_LinkedUavStateTable.getDeploymentOptionId(child), config.redeployCooldownTick());
        }
        return RVP_DeployableUavStateSavedData.beginTermination(
                level, parentUuid, child.getUUID(), "terminating");
    }

    /** 自然致毁爆炸伤害只豁免本tick刚回传且爆炸来源正是该child的玩家。 */
    public static boolean shouldProtectReturnedPlayer(ServerPlayer player, DamageSource source) {
        if (player == null || source == null || !source.is(org.ywzj.vehicle.all.AllDamageTypes.EXPLOSION)
                || !(player.level() instanceof ServerLevel level)) return false;
        ReturnProtection protection = RETURN_PROTECTION.get(player.getUUID());
        Entity direct = source.getDirectEntity();
        return direct != null && isReturnProtectionValid(protection == null ? null : protection.childUuid(),
                direct.getUUID(), protection == null ? Long.MIN_VALUE : protection.damageTick(),
                level.getServer().getTickCount());
    }

    /** 纯判定入口：只匹配刚回传child的directEntity，且仅在登记服务器tick有效。 */
    static boolean isReturnProtectionValid(@Nullable UUID protectedChildUuid, @Nullable UUID directEntityUuid,
                                           long protectionTick, long currentTick) {
        return protectedChildUuid != null && protectedChildUuid.equals(directEntityUuid)
                && protectionTick == currentTick;
    }

    /** 服务器tick开始新tick后回收上一tick的临时乘员保护记录。 */
    public static void expireReturnProtection(MinecraftServer server) {
        if (server != null) RETURN_PROTECTION.entrySet().removeIf(entry ->
                entry.getValue().damageTick() < server.getTickCount());
    }

    private static void collectPassengerTree(Entity entity, java.util.List<Entity> result) {
        for (Entity passenger : new java.util.ArrayList<>(entity.getPassengers())) {
            result.add(passenger);
            collectPassengerTree(passenger, result);
        }
    }

    /** 回传成功且对应爆炸结算完成后释放部署租约；自然毁伤的当前tick伤害窗口由事件处理器延迟到下一tick调用。 */
    public static void finishTermination(ServerLevel level, AbstractVehicle child) {
        if (level == null || child == null) return;
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        if (parentUuid == null || !RVP_DeployableUavStateSavedData.finishTermination(level, parentUuid, child.getUUID())) return;
        AbstractVehicle parent = resolveVehicleByUuid(level, parentUuid);
        if (parent != null && child.getUUID().equals(RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent))) {
            RVP_LinkedUavStateTable.setLinkedChildVehicleUuid(parent, null);
        }
        RVP_UavTerminalEntitySavedData.get(level).markTerminal(child.getUUID());
        RVP_UavLoiterManager.remove(child.getUUID());
        RVP_LinkedUavStateTable.clearLinks(child);
        RVP_LinkedUavStateTable.clearPersistent(child);
        RVP_UavLoiterManager.clearPersistent(child);
        RVP_DeployableUavLinkRegistry.clearByChild(child.getUUID());
        clearLinkedParentLastPosition(child.getUUID());
    }

    /** destroyed child残骸仍在时由现有tick扫描，完成上一tick已回传终结的lease结算。 */
    public static void finishDestroyedTerminationIfReady(ServerLevel level, AbstractVehicle child) {
        if (level == null || child == null || !child.isDestroyed()
                || !RVP_LinkedUavStateTable.isDeployableUavInstance(child)) return;
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        if (parentUuid == null || PENDING_AUTO_RIDE.values().stream().anyMatch(p ->
                p.childUuid().equals(child.getUUID()) && !p.returned())) return;
        commitTermination(level, child);
        finishTermination(level, child);
    }

    /** 服务端每 tick 驱动自动上车重试；跨维查父车并检查玩家仍在发起维度。 */
    public static void onServerTick(MinecraftServer server) {
        RVP_DeployableUavStateSavedData saved = RVP_DeployableUavStateSavedData.get(server.overworld());
        if (!returnsLoaded) {
            saved.pendingReturns().forEach((player, tag) -> {
                ResourceLocation parentDim = ResourceLocation.tryParse(tag.getString("parentDimension"));
                ResourceLocation playerDim = ResourceLocation.tryParse(tag.getString("playerDimension"));
                ResourceLocation returnDim = ResourceLocation.tryParse(tag.getString("dimension"));
                if (parentDim == null) parentDim = Level.OVERWORLD.location();
                if (playerDim == null) playerDim = parentDim;
                PENDING_AUTO_RIDE.putIfAbsent(player, new PendingAutoRide(tag.getUUID("child"), tag.getUUID("parent"),
                        parentDim, playerDim, tag.getInt("seat"), AUTO_RIDE_MAX_RETRY, 0,
                        returnDim, tag.contains("x") ? new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")) : null, false));
            });
            returnsLoaded = true;
        }
        var iterator = PENDING_AUTO_RIDE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingAutoRide> entry = iterator.next();
            UUID playerUuid = entry.getKey();
            PendingAutoRide pending = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null || player.isRemoved()) continue; // 离线保留，登录后接着回传。
            if (!player.isAlive()) {
                saved.clearPendingReturn(playerUuid); // 已真实死亡，不复活或把重生玩家拖回去。
                releaseSeatLockForPlayer(-1, playerUuid, server);
                iterator.remove();
                continue;
            }
            if (server.getTickCount() < pending.notBeforeTick()) continue;
            if (!pending.returned()) {
                pending = tryReturn(player, pending);
                entry.setValue(pending);
                if (!pending.returned() || server.getTickCount() < pending.notBeforeTick()) continue;
            }
            AbstractVehicle parent = resolveVehicleByUuid(server.overworld(), pending.parentUuid());
            PendingRideDecision decision = classifyPendingRide(true, player.level().dimension().location(),
                    player.isPassenger(), parent != null && player.getVehicle() == parent,
                    server.getTickCount(), pending);
            if (decision == PendingRideDecision.CANCEL || decision == PendingRideDecision.ALREADY_PARENT) {
                if (decision == PendingRideDecision.ALREADY_PARENT) restoreSeatAndUnlock(player, parent, pending.seatIndex());
                else releaseSeatLockForPlayer(-1, playerUuid, server);
                iterator.remove();
                continue;
            }
            if (parent != null && !parent.isRemoved() && parent.isAlive() && !parent.isDestroyed()
                    && parent.level() == player.level() && parent.distanceToSqr(player) < 256 && player.startRiding(parent)) {
                restoreSeatAndUnlock(player, parent, pending.seatIndex());
                iterator.remove();
                continue;
            }
            // 只有已经确认回传的“重新入座”会超时；物理回传本身不会静默放弃。
            PendingAutoRide next = decrementPendingRetry(pending);
            if (next == null) {
                releaseSeatLockForPlayer(-1, playerUuid, server);
                iterator.remove();
            } else entry.setValue(next);
        }
        for (var transaction : saved.readyTerminations().entrySet()) {
            UUID parentUuid = transaction.getKey();
            UUID childUuid = transaction.getValue();
            AbstractVehicle child = resolveVehicleByUuid(server.overworld(), childUuid);
            if (child != null) finishTermination((ServerLevel) child.level(), child);
            else if (saved.finishTermination(parentUuid, childUuid)) {
                AbstractVehicle parent = resolveVehicleByUuid(server.overworld(), parentUuid);
                if (parent != null && childUuid.equals(RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent)))
                    RVP_LinkedUavStateTable.setLinkedChildVehicleUuid(parent, null);
                RVP_UavTerminalEntitySavedData.get(server.overworld()).markTerminal(childUuid);
                RVP_UavLoiterManager.remove(childUuid);
                RVP_DeployableUavLinkRegistry.clearByChild(childUuid);
                clearLinkedParentLastPosition(childUuid);
            }
        }
    }

    /** 死亡后的重生不再接续旧回传；跨末地返回这种非死亡重生由调用方排除。 */
    public static void cancelReturnAfterDeath(ServerPlayer player) {
        PENDING_AUTO_RIDE.remove(player.getUUID());
        RVP_DeployableUavStateSavedData.get(player.serverLevel()).clearPendingReturn(player.getUUID());
        releaseSeatLockForPlayer(player.getId(), player.getUUID(), player.server);
    }

    /** 服务器更换时清空座位与延迟控制队列，避免跨世界复用 UUID/tick。 */
    public static void clearRuntime() {
        PENDING_AUTO_RIDE.clear();
        returnsLoaded = false;
        PARENT_SEAT_LOCKS.clear();
        PARENT_LAST_POSITIONS.clear();
        OUT_OF_SIGNAL_RANGE_TICKS.clear();
        RETURN_PROTECTION.clear();
    }

    @Nullable
    private static ServerLevel findServerLevel(MinecraftServer server, @Nullable ResourceLocation dimension) {
        if (server == null || dimension == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dimension)) return level;
        }
        return null;
    }

}
