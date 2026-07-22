package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public final class GunnerExternalRadarController {

    private GunnerExternalRadarController() {}

    public static void tick(GunnerEntity gunner,
                            AbstractVehicle launcher,
                            @Nullable WeaponUnit weaponUnit,
                            @Nullable Entity target,
                            boolean driverAi) {
        if (!driverAi || launcher.level().isClientSide() || weaponUnit == null) {
            return;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF
                || !(root instanceof WeaponUnitExternalRadarLockExt ext)) {
            return;
        }

        AbstractVehicle relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(launcher).orElse(null);
        if ((relayVehicle == null || relayVehicle.isRemoved() || !relayVehicle.isAlive())
                && gunner.tickCount % 20 == 0) {
            RVP_DeployableUavService.deployLinkedUav(launcher, gunner);
            relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(launcher).orElse(null);
        }
        if (relayVehicle == null || relayVehicle.isRemoved() || !relayVehicle.isAlive()) {
            clearExternalLock(root, ext, null);
            return;
        }

        turnOnRelayRadars(relayVehicle);
        RadarUnit lockRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle);
        if (lockRadar == null) {
            clearExternalLock(root, ext, relayVehicle);
            return;
        }

        Entity lockTarget = normalizeTarget(target);
        if (lockTarget == null || !lockTarget.isAlive() || !isWithinRelayLockVolume(lockRadar, lockTarget)) {
            clearExternalLock(root, ext, relayVehicle);
            return;
        }

        lockRadar.detect(lockTarget);
        if (!RVP_RadarRoleHelper.entityMatches(lockRadar.getLockedEntity(), lockTarget.getId())) {
            lockRadar.setLockedEntity(lockTarget);
        }
        if (!RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), lockTarget.getId())) {
            root.setLockedEntity(lockTarget);
        }
        ext.ywzj_rvp$setExternalRadarRequestedEntityId(lockTarget.getId());
        ext.ywzj_rvp$setExternalRadarLockedEntityId(lockTarget.getId());
    }

    private static void turnOnRelayRadars(AbstractVehicle relayVehicle) {
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit && !radarUnit.isOn()) {
                radarUnit.toggle(true);
            }
        }
    }

    @Nullable
    private static Entity normalizeTarget(@Nullable Entity target) {
        if (target instanceof Player player && player.getVehicle() instanceof AbstractVehicle vehicle) {
            return vehicle;
        }
        return target;
    }

    private static boolean isWithinRelayLockVolume(RadarUnit radarUnit, Entity target) {
        Vec3 radarPos = radarUnit.worldRadarPosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        double maxRange = radarUnit.getMaxScanDistance();
        if (targetPos.distanceToSqr(radarPos) > maxRange * maxRange) {
            return false;
        }
        if (!isWithinScanHeight(radarUnit, targetPos)) {
            return false;
        }
        Vec2 aimRot = radarUnit.aimRot(targetPos);
        float yMin = radarUnit.getYRotMin();
        float yMax = radarUnit.getYRotMax();
        float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
        if (!isYawWithin(y, yMin, yMax)) {
            return false;
        }
        return aimRot.x >= radarUnit.getXRotMin() && aimRot.x <= radarUnit.getXRotMax();
    }

    private static boolean isWithinScanHeight(RadarUnit radarUnit, Vec3 targetPos) {
        float minHeight = 25f;
        float maxHeight = 10000f;
        RadarUnitData data = radarUnit.getData();
        if (data instanceof RadarUnitDataExt ext) {
            minHeight = ext.ywzj_rvp$getScanMinHeight();
            maxHeight = ext.ywzj_rvp$getScanMaxHeight();
        }
        if (maxHeight < minHeight) {
            float swap = minHeight;
            minHeight = maxHeight;
            maxHeight = swap;
        }
        int groundY = radarUnit.getVehicle().level().getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(targetPos.x),
                Mth.floor(targetPos.z));
        double agl = targetPos.y - groundY;
        return agl >= minHeight && agl <= maxHeight;
    }

    private static boolean isYawWithin(float y, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return true;
        }
        return y >= yMin && y <= yMax;
    }

    private static float normalizeYawForLimits(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return yaw;
        }
        boolean prefer360Space = yMin >= 0.0f && yMax > 180.0f;
        if (prefer360Space && yaw < 0.0f) {
            return yaw + 360.0f;
        }
        return yaw;
    }

    private static void clearExternalLock(WeaponUnit root,
                                          WeaponUnitExternalRadarLockExt ext,
                                          @Nullable AbstractVehicle relayVehicle) {
        int requestedId = ext.ywzj_rvp$getExternalRadarRequestedEntityId();
        int lockedId = ext.ywzj_rvp$getExternalRadarLockedEntityId();
        Entity localRadarLocked = RVP_RadarRoleHelper.getLockedRadarEntity(root);
        boolean localRadarKeepsLock = RVP_RadarRoleHelper.entityMatches(localRadarLocked, requestedId)
                || RVP_RadarRoleHelper.entityMatches(localRadarLocked, lockedId);
        if (!localRadarKeepsLock
                && (RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), requestedId)
                || RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), lockedId))) {
            root.setLockedEntity(null);
        }
        ext.ywzj_rvp$clearExternalRadarRequestedEntityId();
        ext.ywzj_rvp$clearExternalRadarLockedEntityId();
        if (relayVehicle == null) {
            return;
        }
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit && radarUnit.getLockedEntity() != null) {
                radarUnit.setLockedEntity(null);
            }
        }
    }
}
