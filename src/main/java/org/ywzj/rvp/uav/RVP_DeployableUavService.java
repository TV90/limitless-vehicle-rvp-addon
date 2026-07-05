package org.ywzj.rvp.uav;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Optional;
import java.util.UUID;

public final class RVP_DeployableUavService {
    public enum DeployResult {
        SUCCESS,
        NO_PARENT_VEHICLE,
        NO_CONFIG,
        INVALID_TEMPLATE,
        ALREADY_DEPLOYED,
        SPAWN_FAILED
    }

    private RVP_DeployableUavService() {}

    public static DeployResult deployLinkedUav(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle parent)) {
            return DeployResult.NO_PARENT_VEHICLE;
        }
        RVP_DeployableUavConfig config = RVP_DeployableUavConfigCache.get(parent.getVehicleId());
        if (!config.isConfigured()) {
            return DeployResult.NO_CONFIG;
        }
        if (!(parent.level() instanceof ServerLevel serverLevel)) {
            return DeployResult.SPAWN_FAILED;
        }
        if (config.singleInstance()) {
            AbstractVehicle existing = getLinkedChild(parent).orElse(null);
            if (existing != null && existing.isAlive() && !existing.isRemoved()) {
                return DeployResult.ALREADY_DEPLOYED;
            }
            clearLinkedChild(parent);
        }

        ResourceLocation childVehicleId = config.vehicleId();
        BaseVehicleData<?> childData = CommonAssetsManager.vehicleDataManager().getVehicleData(childVehicleId).orElse(null);
        if (childData == null) {
            return DeployResult.INVALID_TEMPLATE;
        }

        float spawnYaw = resolveSpawnYaw(parent, player, config);
        Vec3 spawnPos = resolveSpawnPos(parent, config.spawnOffset(), spawnYaw);
        AbstractVehicle child = childData.construct(serverLevel, spawnPos, 0.0f, spawnYaw);
        if (child == null) {
            return DeployResult.SPAWN_FAILED;
        }
        configureLink(parent, child, player, config);
        serverLevel.addFreshEntity(child);
        return DeployResult.SUCCESS;
    }

    public static boolean switchToLinkedUav(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle parent)) {
            return false;
        }
        AbstractVehicle child = getLinkedChild(parent).orElse(null);
        if (child == null || child.isRemoved() || !child.isAlive()) {
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

    private static void configureLink(AbstractVehicle parent, AbstractVehicle child, ServerPlayer player, RVP_DeployableUavConfig config) {
        if (parent instanceof AbstractVehicleLinkedUavExt parentExt) {
            parentExt.ywzj_rvp$setLinkedChildVehicleUuid(child.getUUID());
        }
        RVP_DeployableUavLinkRegistry.link(parent.getUUID(), child.getUUID());
        if (child instanceof AbstractVehicleLinkedUavExt childExt) {
            childExt.ywzj_rvp$setDeployableUavInstance(true);
            childExt.ywzj_rvp$setLinkedParentVehicleUuid(parent.getUUID());
            childExt.ywzj_rvp$setLinkedLauncherVehicleUuid(config.autoLinkDatalink() ? parent.getUUID() : null);
            childExt.ywzj_rvp$setReturnSeatIndex(findSeatIndex(parent, player));
            childExt.ywzj_rvp$setDeployableUavRole(config.role());
            childExt.ywzj_rvp$setDatalinkRole(config.autoLinkDatalink() ? config.role() : "none");
            childExt.ywzj_rvp$setDeployableUavControlSwitchAllowed(config.allowControlSwitch());
        }
    }

    private static int findSeatIndex(AbstractVehicle vehicle, ServerPlayer player) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId != null && seat.passengerId == player.getId()) {
                return seat.seatIndex;
            }
        }
        return 0;
    }

    private static float resolveSpawnYaw(AbstractVehicle parent, ServerPlayer player, RVP_DeployableUavConfig config) {
        return switch (config.spawnYawMode().toLowerCase()) {
            case "operator_look" -> player.getYRot();
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

    private static AbstractVehicle resolveVehicleByUuid(net.minecraft.world.level.Level level, UUID uuid) {
        if (uuid == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(uuid);
        return entity instanceof AbstractVehicle vehicle ? vehicle : null;
    }
}
