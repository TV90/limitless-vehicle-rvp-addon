package org.ywzj.rvp.uav;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
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

    private record PendingAutoRide(UUID parentUuid, int seatIndex, int retryLeft) {}

    /** 待自动上车队列（key = 玩家 UUID）：玩家离开无人机后，母车实体不可用时延迟重试。 */
    private static final Map<UUID, PendingAutoRide> PENDING_AUTO_RIDE = new HashMap<>();

    /** 母车座位锁信息（玩家驾驶无人机期间锁定母车座位）。 */
    public record SeatLockInfo(int seatIndex, int ownerPlayerId) {}

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
            clearLinkedChild(parent);
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
        configureLink(parent, child, operator, config);
        serverLevel.addFreshEntity(child);
        applyInitialSpeed(child, spawnYaw, config.initialSpeed());
        applyTakeoffBehavior(child, parent);
        return DeployResult.SUCCESS;
    }

    public static boolean switchToLinkedUav(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle parent)) {
            return false;
        }
        AbstractVehicle child = getLinkedChild(parent).orElse(null);
        if (child == null || child.isRemoved() || !child.isAlive() || child.isDestroyed()) {
            clearLinkedChild(parent);
            return false;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavControlSwitchAllowed(child)) {
            return false;
        }
        RVP_LinkedUavStateTable.setReturnSeatIndex(child, findSeatIndex(parent, player));
        boolean riding = player.startRiding(child);
        if (!riding) {
            return false;
        }
        if (!child.seats.isEmpty() && child.seats.get(0).passengerId != player.getId()) {
            child.changeSeat(player, 0);
        }
        // 锁定母车上玩家离开前的座位（通常为驾驶位），防止他人占用/开走母车。
        // 玩家切回母车或自动上车成功时由 clearSeatLock 解除。
        // 锁状态存于静态注册表（不在实体上新增接口方法，避免 mixin 接口注入风险）。
        setSeatLock(parent, RVP_LinkedUavStateTable.getReturnSeatIndex(child), player.getId());
        // 玩家进入无人机后盘旋继续（运动输入由 ControlUnitMixin 屏蔽）；
        // 玩家可按 F 键手动切换盘旋开/关
        return true;
    }

    public static boolean switchBackToParent(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle child)) {
            LOGGER.info("[RVP-UAV] 切回失败：玩家不在载具上 vehicle={}", player.getVehicle());
            return false;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(child)) {
            LOGGER.info("[RVP-UAV] 切回失败：{} 非可部署UAV实例", child.getVehicleId());
            return false;
        }
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(child);
        AbstractVehicle parent = resolveVehicleByUuid(child.level(), parentUuid);
        if (parent == null || parent.isRemoved() || !parent.isAlive() || parent.isDestroyed()) {
            // 母车实体暂不可用（区块卸载/已被移除）：直接下车，由 handleDismount 回传母车旁并
            // 进入延迟自动上车队列（母车区块重载后自动骑乘），避免玩家卡在无人机上。
            LOGGER.info("[RVP-UAV] 母车暂不可用，下车回传: uav={} parentUuid={} parent={} removed={} alive={}",
                    child.getVehicleId(),
                    parentUuid,
                    parent == null ? "null" : parent.getVehicleId(),
                    parent != null && parent.isRemoved(),
                    parent != null && parent.isAlive());
            if (player.getVehicle() == child) {
                player.stopRiding();
            }
            return true;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavControlSwitchAllowed(child)) {
            LOGGER.info("[RVP-UAV] 切回失败：控制切换被禁用 child={}", child.getVehicleId());
            return false;
        }
        boolean riding = player.startRiding(parent);
        if (!riding) {
            LOGGER.info("[RVP-UAV] 切回失败：startRiding返回false parent={}", parent.getVehicleId());
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
        // 清除盘旋状态
        RVP_UavLoiterManager.remove(child.getUUID());
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
        RVP_DeployableUavLinkRegistry.clearByChild(child.getUUID());
    }

    private static void configureLink(AbstractVehicle parent, AbstractVehicle child, @Nullable LivingEntity operator, RVP_DeployableUavConfig config) {
        RVP_LinkedUavStateTable.setLinkedChildVehicleUuid(parent, child.getUUID());
        RVP_DeployableUavLinkRegistry.link(parent.getUUID(), child.getUUID());
        RVP_LinkedUavStateTable.setDeployableUavInstance(child, true);
        RVP_LinkedUavStateTable.setLinkedParentVehicleUuid(child, parent.getUUID());
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
        if (uuid == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(uuid);
        return entity instanceof AbstractVehicle vehicle ? vehicle : null;
    }

    // ==================== 自动上车 + 母车座位锁 ====================

    /**
     * tryAutoRideParent 重入保护（服务端单线程，静态标志足够）。
     *
     * <p>Forge 的 {@code Entity#removeVehicle()}（即 stopRiding）在置空 vehicle 字段<strong>之前</strong>
     * 就触发 dismount 事件（canMountEntity），因此 dismount 事件处理时 {@code player.getVehicle()} 仍是
     * 旧坐骑（非 null）。本方法内部的 {@code player.startRiding(parent)} 会触发旧坐骑 stopRiding → 再触发
     * dismount 事件 → 再回到本方法，形成递归链。</p>
     *
     * <p>旧守卫（{@code player.getVehicle() != null} 时跳过）虽能拦截递归，但同样误伤无人机被击毁/移除的
     * 死亡离机场景（该场景 dismount 事件同样发生在 vehicle 置空前），导致只传回母车旁、自动上车失效。
     * 改为统一的重入保护：递归链由最外层调用完成自动上车。</p>
     */
    private static boolean autoRideInProgress = false;

    /**
     * 玩家离开无人机（被击毁/主动切回）后：传送回母车旁并自动坐上母车座位。
     * 母车实体不可用（区块卸载）时进入延迟重试队列，等待母车重新加载后自动上车。
     */
    public static void tryAutoRideParent(ServerPlayer player, AbstractVehicle uav) {
        if (player == null || uav == null || player.level().isClientSide()) {
            return;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(uav)) {
            return;
        }
        if (autoRideInProgress) {
            // 重入：本方法内部 startRiding → 旧坐骑 stopRiding → dismount 事件 再触发本方法。
            // 交给最外层调用完成自动上车，避免无限递归。
            return;
        }
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav);
        if (parentUuid == null) {
            return;
        }
        int seatIndex = Math.max(0, RVP_LinkedUavStateTable.getReturnSeatIndex(uav));
        autoRideInProgress = true;
        try {
            AbstractVehicle parent = resolveVehicleByUuid(uav.level(), parentUuid);
            if (parent == null || parent.isRemoved() || !parent.isAlive() || parent.isDestroyed()) {
                scheduleAutoRide(player.getUUID(), parentUuid, seatIndex);
                return;
            }
            if (player.getVehicle() == parent) {
                // 已回到母车（主动切回时 onLeaveVehicle 先于 startRiding 触发）
                restoreSeatAndUnlock(player, parent, seatIndex);
                return;
            }
            if (player.getVehicle() == uav) {
                // dismount 事件尚未完成移除（vehicle 字段仍指向无人机）时同步 startRiding 母车，
                // 会嵌套触发旧坐骑 stopRiding → 再次 dismount 事件 → 递归返回后外层 stopRiding 因
                // getVehicle()!=this 守卫失败，留下：① 母车 onEnterVehicle 二次执行占两个座位
                // （两个座位栏目显示同一玩家）；② 无人机乘客列表残留幻影乘客。
                // 改为延迟到下一 tick（removePassenger 完成后 player.getVehicle()==null）再自动上车。
                scheduleAutoRide(player.getUUID(), parentUuid, seatIndex);
                return;
            }
            if (player.startRiding(parent)) {
                restoreSeatAndUnlock(player, parent, seatIndex);
            } else {
                scheduleAutoRide(player.getUUID(), parentUuid, seatIndex);
            }
        } finally {
            autoRideInProgress = false;
        }
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
        if (lock != null && lock.ownerPlayerId() == owner.getId()) {
            PARENT_SEAT_LOCKS.remove(parent.getUUID());
            LOGGER.info("[RVP-UAV-LOCK] 解锁母车 {} seat={} owner={}",
                    parent.getVehicleId(), lock.seatIndex(), lock.ownerPlayerId());
        }
    }

    /** 设置母车座位锁（玩家切至无人机期间，防止他人占用/开走母车）。 */
    public static void setSeatLock(AbstractVehicle parent, int seatIndex, int ownerPlayerId) {
        if (parent == null || seatIndex < 0) {
            LOGGER.info("[RVP-UAV-LOCK] setSeatLock 跳过 parent={} seatIndex={}",
                    parent == null ? "null" : parent.getVehicleId(), seatIndex);
            return;
        }
        PARENT_SEAT_LOCKS.put(parent.getUUID(), new SeatLockInfo(seatIndex, ownerPlayerId));
        LOGGER.info("[RVP-UAV-LOCK] 锁母车 {} seat={} owner={}",
                parent.getVehicleId(), seatIndex, ownerPlayerId);
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
        boolean reject = passenger == null || passenger.getId() != lock.ownerPlayerId();
        if (reject) {
            LOGGER.info("[RVP-UAV-LOCK] 拒绝 {} 上母车 {}（锁 owner={} seat={}）",
                    passenger == null ? "null" : passenger.getName().getString(),
                    vehicle.getVehicleId(), lock.ownerPlayerId(), lock.seatIndex());
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

    /** 母车实体移除时清理其座位锁。 */
    public static void cleanupSeatLock(AbstractVehicle parent) {
        if (parent != null) {
            PARENT_SEAT_LOCKS.remove(parent.getUUID());
        }
    }

    /** 玩家重生/离开时解锁其锁定的所有母车座位（防止座位被永久锁死）。 */
    public static void unlockSeatForPlayer(Level level, int playerId) {
        PARENT_SEAT_LOCKS.entrySet().removeIf(entry -> entry.getValue().ownerPlayerId() == playerId);
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
            // 母车实体真正移除/卸载时由 RVP_LinkedUavEventHandler.onEntityLeaveWorld -> cleanupSeatLock 清理。
            if (!(serverLevel.getEntity(entry.getKey()) instanceof AbstractVehicle parent)) {
                continue;
            }
            Optional<AbstractVehicle.Seat> lockedSeat = parent.seats.stream()
                    .filter(seat -> seat.seatIndex == lock.seatIndex())
                    .findFirst();
            if (lockedSeat.isEmpty() || lockedSeat.get().passengerId == -1) {
                continue;
            }
            if (lockedSeat.get().passengerId == lock.ownerPlayerId()) {
                continue; // 持有者放行
            }
            if (!(parent.level().getEntity(lockedSeat.get().passengerId) instanceof ServerPlayer serverPlayer)) {
                continue;
            }
            // 直接踢出而非换座：母车锁定的语义是"锁住母车"，入侵者不应留在母车任意座位上
            // （配合 onMount 上车事件拦截，此处的每 tick 强制定位为兜底）。
            LOGGER.info("[RVP-UAV-LOCK] 每tick兜底踢出 {}（母车 {} 锁 seat={} owner={}）",
                    serverPlayer.getName().getString(), parent.getVehicleId(),
                    lock.seatIndex(), lock.ownerPlayerId());
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

    private static void scheduleAutoRide(UUID playerUuid, UUID parentUuid, int seatIndex) {
        if (playerUuid == null || parentUuid == null) {
            return;
        }
        PENDING_AUTO_RIDE.put(playerUuid, new PendingAutoRide(parentUuid, seatIndex, AUTO_RIDE_MAX_RETRY));
    }

    /** 服务端每 tick 驱动自动上车重试（由 {@link org.ywzj.rvp.event.RVP_UavSeatLockEventHandler} 调用）。 */
    public static void onServerTick(ServerLevel serverLevel) {
        if (PENDING_AUTO_RIDE.isEmpty()) {
            return;
        }
        PENDING_AUTO_RIDE.entrySet().removeIf(entry -> {
            UUID playerUuid = entry.getKey();
            PendingAutoRide pending = entry.getValue();
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerUuid);
            if (player == null || player.isRemoved()) {
                return true; // 玩家离线/移除，放弃重试
            }
            if (player.getVehicle() != null) {
                return true; // 已骑乘（手动上车/切回），不再处理
            }
            AbstractVehicle parent = resolveVehicleByUuid(serverLevel, pending.parentUuid());
            if (parent == null || parent.isRemoved() || !parent.isAlive() || parent.isDestroyed()) {
                return pending.retryLeft() <= 1;
            }
            if (player.startRiding(parent)) {
                restoreSeatAndUnlock(player, parent, pending.seatIndex());
                return true;
            }
            return pending.retryLeft() <= 1;
        });
    }
}
