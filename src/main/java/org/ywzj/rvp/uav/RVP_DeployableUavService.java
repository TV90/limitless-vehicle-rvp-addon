package org.ywzj.rvp.uav;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

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

    record PendingAutoRide(UUID parentUuid, ResourceLocation parentDimension,
                           ResourceLocation playerDimension, int seatIndex, int retryLeft,
                           long notBeforeTick, @Nullable ResourceLocation returnDimension,
                           @Nullable Vec3 returnPosition) {}

    enum PendingRideDecision { CANCEL, ALREADY_PARENT, WAIT, READY }

    /** 待自动上车队列（key = 玩家 UUID）：玩家离开无人机后，母车实体不可用时延迟重试。 */
    private static final Map<UUID, PendingAutoRide> PENDING_AUTO_RIDE = new HashMap<>();

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
        return new PendingAutoRide(pending.parentUuid(), pending.parentDimension(), pending.playerDimension(),
                pending.seatIndex(), retryLeft, pending.notBeforeTick(), pending.returnDimension(),
                pending.returnPosition());
    }

    /** 母车座位锁信息（玩家驾驶无人机期间锁定母车座位）。 */
    public record SeatLockInfo(int seatIndex, int ownerPlayerId, UUID ownerPlayerUuid) {}

    /** 母车座位锁注册表（key = 母车实体 UUID）。 */
    private static final Map<UUID, SeatLockInfo> PARENT_SEAT_LOCKS = new HashMap<>();

    /** 母车（父车）最近一次同步到的位置快照（key = 无人机实体 UUID）：母车区块卸载后被击毁仍能据此回传。 */
    private static final Map<UUID, Vec3> PARENT_LAST_POSITIONS = new HashMap<>();

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
        RVP_DeployableUavConfig config = RVP_DeployableUavConfigCache.get(parent.getVehicleId());
        if (!config.isConfigured()) {
            return DeployResult.NO_CONFIG;
        }
        if (operator instanceof ServerPlayer serverPlayer && !config.isSeatAllowed(findSeatIndex(parent, serverPlayer))) {
            return DeployResult.SEAT_NOT_ALLOWED;
        }
        if (!(parent.level() instanceof ServerLevel serverLevel)) {
            return DeployResult.SPAWN_FAILED;
        }
        if (RVP_DeployableUavCooldownRegistry.getRemainingTick(serverLevel, parent.getUUID()) > 0) {
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
        // 切回母车 → 自动激活盘旋
        RVP_DeployableUavConfig uavConfig = RVP_DeployableUavConfigCache.get(parent.getVehicleId());
        if (uavConfig.isConfigured() && uavConfig.autoLoiterOnSwitchBack()) {
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
        if (child.level() instanceof ServerLevel serverLevel) {
            RVP_UavTerminalEntitySavedData.get(serverLevel).markTerminal(child.getUUID());
        }
        // 清除盘旋状态
        RVP_UavLoiterManager.remove(child.getUUID());
        OUT_OF_SIGNAL_RANGE_TICKS.remove(child.getUUID());
        AbstractVehicle parent = resolveVehicleByUuid(child.level(), RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child));
        if (parent != null) {
            UUID linkedChildUuid = RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent);
            if (linkedChildUuid != null && linkedChildUuid.equals(child.getUUID())) {
                RVP_LinkedUavStateTable.setLinkedChildVehicleUuid(parent, null);
            }
            if (parent.level() instanceof ServerLevel serverLevel) {
                RVP_DeployableUavConfig config = RVP_DeployableUavConfigCache.get(parent.getVehicleId());
                if (config.isConfigured() && config.redeployCooldownTick() > 0) {
                    RVP_DeployableUavCooldownRegistry.startCooldown(serverLevel, parent.getUUID(), config.redeployCooldownTick());
                }
            }
        }
        RVP_LinkedUavStateTable.clearLinks(child);
        RVP_UavLoiterManager.clearPersistent(child);
        RVP_DeployableUavLinkRegistry.clearByChild(child.getUUID());
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
        RVP_DeployableUavConfig config = RVP_DeployableUavConfigCache.get(parent.getVehicleId());
        if (!config.isConfigured()) {
            return false;
        }
        double range = config.effectiveSignalRange();
        double dx = uav.getX() - parent.getX();
        double dz = uav.getZ() - parent.getZ();
        return dx * dx + dz * dz > range * range;
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
        RVP_DeployableUavConfig config = RVP_DeployableUavConfigCache.get(parent.getVehicleId());
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
     * 释放子载具后根据 LoiterConfig 配置起飞行为：自动进入盘旋（固定翼同时启动引擎并满油门）。
     */
    private static void applyTakeoffBehavior(AbstractVehicle child, AbstractVehicle parent) {
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
        ResourceLocation parentDimension = RVP_LinkedUavStateTable.getLinkedParentDimension(uav);
        if (parentDimension == null) parentDimension = player.level().dimension().location();
        long nextTick = player.level().getServer().getTickCount() + 1L;
        PENDING_AUTO_RIDE.put(player.getUUID(), new PendingAutoRide(parentUuid, parentDimension,
                player.level().dimension().location(), seatIndex, AUTO_RIDE_MAX_RETRY, nextTick,
                returnDimension, returnPosition));
    }

    /** 服务端每 tick 驱动自动上车重试；跨维查父车并检查玩家仍在发起维度。 */
    public static void onServerTick(MinecraftServer server) {
        if (PENDING_AUTO_RIDE.isEmpty()) {
            return;
        }
        var iterator = PENDING_AUTO_RIDE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingAutoRide> entry = iterator.next();
            UUID playerUuid = entry.getKey();
            PendingAutoRide pending = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null || player.isRemoved() || !player.isAlive()) {
                releaseSeatLockForPlayer(-1, playerUuid, server);
                iterator.remove();
                continue;
            }
            AbstractVehicle parent = resolveVehicleByUuid(server.overworld(), pending.parentUuid());
            PendingRideDecision decision = classifyPendingRide(true, player.level().dimension().location(),
                    player.getVehicle() != null, player.getVehicle() == parent && parent != null,
                    server.getTickCount(), pending);
            if (decision == PendingRideDecision.CANCEL) {
                unlockSeatForPlayer(player.level(), player.getId());
                iterator.remove();
                continue;
            }
            if (decision == PendingRideDecision.ALREADY_PARENT) {
                restoreSeatAndUnlock(player, parent, pending.seatIndex());
                iterator.remove();
                continue;
            }
            if (decision == PendingRideDecision.WAIT) {
                continue;
            }
            if (parent != null && parent.level().dimension().location().equals(pending.parentDimension())
                    && !parent.isRemoved() && parent.isAlive() && !parent.isDestroyed()) {
                if (player.level() != parent.level() && parent.level() instanceof ServerLevel parentLevel) {
                    Vec3 transferPos = pending.returnPosition() != null
                            && parentLevel.dimension().location().equals(pending.returnDimension())
                            ? pending.returnPosition() : parent.position().add(0, 1.0, 0);
                    player.teleportTo(parentLevel, transferPos.x, transferPos.y, transferPos.z,
                            java.util.Set.of(), player.getYRot(), player.getXRot());
                    pending = new PendingAutoRide(pending.parentUuid(), pending.parentDimension(),
                            parent.level().dimension().location(), pending.seatIndex(), pending.retryLeft(),
                            server.getTickCount() + 1L, pending.returnDimension(), pending.returnPosition());
                    entry.setValue(pending);
                    continue;
                }
                if (pending.returnPosition() != null && player.level().dimension().location().equals(pending.returnDimension())
                        && player.position().distanceToSqr(pending.returnPosition()) > 4.0) {
                    player.teleportTo(pending.returnPosition().x, pending.returnPosition().y, pending.returnPosition().z);
                }
                if (player.startRiding(parent)) {
                    restoreSeatAndUnlock(player, parent, pending.seatIndex());
                    iterator.remove();
                    continue;
                }
            }
            PendingAutoRide nextRetry = decrementPendingRetry(pending);
            if (nextRetry == null) {
                if (pending.returnPosition() != null) {
                    ServerLevel returnLevel = findServerLevel(server, pending.returnDimension());
                    if (returnLevel != null) {
                        if (player.level() == returnLevel) {
                            player.teleportTo(pending.returnPosition().x, pending.returnPosition().y,
                                    pending.returnPosition().z);
                        } else {
                            player.teleportTo(returnLevel, pending.returnPosition().x, pending.returnPosition().y,
                                    pending.returnPosition().z, java.util.Set.of(), player.getYRot(), player.getXRot());
                        }
                    }
                }
                unlockSeatForPlayer(player.level(), player.getId());
                iterator.remove();
            } else {
                entry.setValue(nextRetry);
            }
        }
    }

    /** 服务器更换时清空座位与延迟控制队列，避免跨世界复用 UUID/tick。 */
    public static void clearRuntime() {
        PENDING_AUTO_RIDE.clear();
        PARENT_SEAT_LOCKS.clear();
        PARENT_LAST_POSITIONS.clear();
        OUT_OF_SIGNAL_RANGE_TICKS.clear();
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
