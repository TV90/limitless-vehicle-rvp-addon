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
        if (parent == null || parent.isRemoved() || !parent.isAlive()) {
            LOGGER.info("[RVP-UAV] 切回失败：母车不可用 parentUuid={} parent={} removed={} alive={}",
                    parentUuid,
                    parent == null ? "null" : parent.getVehicleId(),
                    parent != null && parent.isRemoved(),
                    parent != null && parent.isAlive());
            return false;
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
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav);
        if (parentUuid == null) {
            return;
        }
        int seatIndex = Math.max(0, RVP_LinkedUavStateTable.getReturnSeatIndex(uav));
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
        if (player.getVehicle() != null) {
            // 防递归：玩家仍骑在旧坐骑（如正在被 startRiding 内部 stopRiding 移除的无人机）上。
            // 此时再 startRiding(parent) 会再次触发 stopRiding → dismount 事件 → 回到本方法，形成无限递归。
            // 直接放弃本次自动上车，由最外层显式 startRiding（switchBackToParent）完成骑乘。
            LOGGER.info("[RVP-UAV] tryAutoRideParent 跳过：玩家仍骑在 {} 上，等待切换完成",
                    player.getVehicle().getClass().getSimpleName());
            return;
        }
        if (player.startRiding(parent)) {
            restoreSeatAndUnlock(player, parent, seatIndex);
        } else {
            scheduleAutoRide(player.getUUID(), parentUuid, seatIndex);
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
        }
    }

    /** 设置母车座位锁（玩家切至无人机期间，防止他人占用/开走母车）。 */
    public static void setSeatLock(AbstractVehicle parent, int seatIndex, int ownerPlayerId) {
        if (parent == null || seatIndex < 0) {
            return;
        }
        PARENT_SEAT_LOCKS.put(parent.getUUID(), new SeatLockInfo(seatIndex, ownerPlayerId));
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
        java.util.List<UUID> stale = new java.util.ArrayList<>();
        for (java.util.Map.Entry<UUID, SeatLockInfo> entry : PARENT_SEAT_LOCKS.entrySet()) {
            SeatLockInfo lock = entry.getValue();
            if (!(serverLevel.getEntity(entry.getKey()) instanceof AbstractVehicle parent)) {
                stale.add(entry.getKey()); // 母车已卸载/移除：清理锁
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
            Optional<AbstractVehicle.Seat> emptySeat = parent.seats.stream()
                    .filter(seat -> seat.passengerId == -1 && seat.seatIndex != lock.seatIndex())
                    .findFirst();
            if (emptySeat.isPresent()) {
                parent.changeSeat(serverPlayer, emptySeat.get().seatIndex);
            } else {
                serverPlayer.stopRiding();
            }
        }
        for (UUID uuid : stale) {
            PARENT_SEAT_LOCKS.remove(uuid);
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
