package org.ywzj.rvp.uav;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

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
        SPAWN_FAILED
    }

    private RVP_DeployableUavService() {}

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
        if (!(child instanceof AbstractVehicleLinkedUavExt childExt) || !childExt.ywzj_rvp$isDeployableUavControlSwitchAllowed()) {
            return false;
        }
        childExt.ywzj_rvp$setReturnSeatIndex(findSeatIndex(parent, player));
        boolean riding = player.startRiding(child);
        if (!riding) {
            return false;
        }
        if (!child.seats.isEmpty() && child.seats.get(0).passengerId != player.getId()) {
            child.changeSeat(player, 0);
        }
        // 玩家进入无人机后盘旋继续（运动输入由 ControlUnitMixin 屏蔽）；
        // 玩家可按 F 键手动切换盘旋开/关
        return true;
    }

    public static boolean switchBackToParent(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle child)) {
            return false;
        }
        if (!(child instanceof AbstractVehicleLinkedUavExt childExt) || !childExt.ywzj_rvp$isDeployableUavInstance()) {
            return false;
        }
        AbstractVehicle parent = resolveVehicleByUuid(child.level(), childExt.ywzj_rvp$getLinkedParentVehicleUuid());
        if (parent == null || parent.isRemoved() || !parent.isAlive()) {
            return false;
        }
        if (!childExt.ywzj_rvp$isDeployableUavControlSwitchAllowed()) {
            return false;
        }
        boolean riding = player.startRiding(parent);
        if (!riding) {
            return false;
        }
        int returnSeatIndex = childExt.ywzj_rvp$getReturnSeatIndex();
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
        UUID uuid = null;
        if (parent instanceof AbstractVehicleLinkedUavExt ext) {
            uuid = ext.ywzj_rvp$getLinkedChildVehicleUuid();
        }
        if (uuid == null) {
            uuid = RVP_DeployableUavLinkRegistry.getChildUuid(parent.getUUID());
        }
        return Optional.ofNullable(resolveVehicleByUuid(parent.level(), uuid));
    }

    public static Optional<AbstractVehicle> getLinkedParent(AbstractVehicle child) {
        UUID uuid = null;
        if (child instanceof AbstractVehicleLinkedUavExt ext) {
            uuid = ext.ywzj_rvp$getLinkedParentVehicleUuid();
        }
        if (uuid == null) {
            uuid = RVP_DeployableUavLinkRegistry.getParentUuid(child.getUUID());
        }
        return Optional.ofNullable(resolveVehicleByUuid(child.level(), uuid));
    }

    public static void clearLinkedChild(AbstractVehicle parent) {
        if (parent instanceof AbstractVehicleLinkedUavExt ext) {
            ext.ywzj_rvp$setLinkedChildVehicleUuid(null);
        }
        RVP_DeployableUavLinkRegistry.clearByParent(parent.getUUID());
    }

    public static int getRedeployCooldownRemainingTick(AbstractVehicle parent) {
        if (parent == null || !(parent.level() instanceof ServerLevel serverLevel)) {
            return 0;
        }
        return RVP_DeployableUavCooldownRegistry.getRemainingTick(serverLevel, parent.getUUID());
    }

    public static void handleDeployableUavRemoved(AbstractVehicle child) {
        if (!(child instanceof AbstractVehicleLinkedUavExt childExt) || !childExt.ywzj_rvp$isDeployableUavInstance()) {
            return;
        }
        // 清除盘旋状态
        RVP_UavLoiterManager.remove(child.getUUID());
        AbstractVehicle parent = resolveVehicleByUuid(child.level(), childExt.ywzj_rvp$getLinkedParentVehicleUuid());
        if (parent instanceof AbstractVehicleLinkedUavExt parentExt) {
            if (parentExt.ywzj_rvp$getLinkedChildVehicleUuid() != null
                    && parentExt.ywzj_rvp$getLinkedChildVehicleUuid().equals(child.getUUID())) {
                parentExt.ywzj_rvp$setLinkedChildVehicleUuid(null);
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
        if (parent instanceof AbstractVehicleLinkedUavExt parentExt) {
            parentExt.ywzj_rvp$setLinkedChildVehicleUuid(child.getUUID());
        }
        RVP_DeployableUavLinkRegistry.link(parent.getUUID(), child.getUUID());
        if (child instanceof AbstractVehicleLinkedUavExt childExt) {
            childExt.ywzj_rvp$setDeployableUavInstance(true);
            childExt.ywzj_rvp$setLinkedParentVehicleUuid(parent.getUUID());
            childExt.ywzj_rvp$setLinkedLauncherVehicleUuid(config.autoLinkDatalink() ? parent.getUUID() : null);
            childExt.ywzj_rvp$setReturnSeatIndex(findSeatIndex(parent, operator));
            childExt.ywzj_rvp$setDeployableUavRole(config.role());
            childExt.ywzj_rvp$setDatalinkRole(config.autoLinkDatalink() ? config.role() : "none");
            childExt.ywzj_rvp$setDeployableUavControlSwitchAllowed(config.allowControlSwitch());
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
     * 释放子载具后根据 LoiterConfig 配置起飞行为：自动进入盘旋、自动满节流阀。
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
        }
        if (loiterConfig.autoFullThrottleOnTakeoff() && child instanceof FixedWingVehicle fw) {
            fw.toggleEngine(true);
            fw.setPower(100f);
            fw.setThrottleLevel(100f);
        }
    }

    private static AbstractVehicle resolveVehicleByUuid(net.minecraft.world.level.Level level, UUID uuid) {
        if (uuid == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(uuid);
        return entity instanceof AbstractVehicle vehicle ? vehicle : null;
    }
}
