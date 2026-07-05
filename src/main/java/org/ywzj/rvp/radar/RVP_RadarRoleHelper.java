package org.ywzj.rvp.radar;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.ext.WeaponUnitPendingRadarLockExt;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashSet;
import java.util.Set;

public final class RVP_RadarRoleHelper {
    public static final String ROLE_ALL = "ALL";
    public static final String ROLE_SEARCH = "SEARCH";
    public static final String ROLE_FIRE_CONTROL = "FIRE_CONTROL";
    public static final float MANUAL_LOCK_REQUEST_FOV = 18.0f;

    private RVP_RadarRoleHelper() {}

    public static String getRadarRole(RadarUnit radarUnit) {
        if (radarUnit == null) {
            return ROLE_ALL;
        }
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext.ywzj_rvp$getRadarRole();
        }
        return ROLE_ALL;
    }

    public static boolean canSearch(RadarUnit radarUnit) {
        return radarUnit != null;
    }

    public static boolean canLock(RadarUnit radarUnit) {
        return radarUnit != null && !ROLE_SEARCH.equalsIgnoreCase(getRadarRole(radarUnit));
    }

    public static RadarUnit getPreferredLockRadar(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        RadarUnit firstLockCapable = null;
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!canLock(radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            if (ROLE_FIRE_CONTROL.equalsIgnoreCase(getRadarRole(radarUnit))) {
                return radarUnit;
            }
            if (firstLockCapable == null) {
                firstLockCapable = radarUnit;
            }
        }
        return firstLockCapable;
    }

    public static RadarUnit getLockedRadar(WeaponUnit weaponUnit) {
        RadarUnit preferred = getPreferredLockRadar(weaponUnit);
        if (preferred != null && preferred.getLockedEntity() != null) {
            return preferred;
        }
        if (weaponUnit == null) {
            return null;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit.getLockedEntity() != null) {
                return radarUnit;
            }
        }
        return preferred;
    }

    public static Entity getLockedRadarEntity(WeaponUnit weaponUnit) {
        RadarUnit radarUnit = getLockedRadar(weaponUnit);
        return radarUnit != null ? radarUnit.getLockedEntity() : null;
    }

    public static void clearAllRadarLocks(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit.getLockedEntity() != null) {
                radarUnit.setLockedEntity(null);
            }
        }
        clearPendingRadarLock(weaponUnit);
    }

    public static boolean applyRequestedLock(WeaponUnit weaponUnit, Entity target) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        RadarUnit lockRadar = getPreferredLockRadar(weaponUnit);
        if (lockRadar == null) {
            return false;
        }
        weaponUnit.setFocusLockPos(null);
        if (!entityMatches(weaponUnit.getLockedEntity(), target.getId())) {
            weaponUnit.setLockedEntity(target);
        }
        if (radarCurrentlyDetects(lockRadar, target)) {
            if (!entityMatches(lockRadar.getLockedEntity(), target.getId())) {
                clearOtherRadarLocks(weaponUnit, lockRadar);
                lockRadar.setLockedEntity(target);
            }
            clearPendingRadarLock(weaponUnit);
        } else {
            setPendingRadarLock(weaponUnit, target.getId());
        }
        return true;
    }

    public static void tickPendingRadarLock(WeaponUnit weaponUnit) {
        if (!(weaponUnit instanceof WeaponUnitPendingRadarLockExt ext)) {
            return;
        }
        int pendingId = ext.ywzj_rvp$getPendingRadarLockEntityId();
        if (pendingId == Integer.MIN_VALUE) {
            return;
        }
        RadarUnit lockRadar = getPreferredLockRadar(weaponUnit);
        if (lockRadar == null) {
            ext.ywzj_rvp$clearPendingRadarLockEntityId();
            return;
        }
        Entity target = weaponUnit.getVehicle().level().getEntity(pendingId);
        if (target == null || !target.isAlive()) {
            ext.ywzj_rvp$clearPendingRadarLockEntityId();
            if (entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
                weaponUnit.setLockedEntity(null);
            }
            return;
        }
        if (radarCurrentlyDetects(lockRadar, target)) {
            if (!entityMatches(lockRadar.getLockedEntity(), pendingId)) {
                clearOtherRadarLocks(weaponUnit, lockRadar);
                lockRadar.setLockedEntity(target);
            }
            if (!entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
                weaponUnit.setLockedEntity(target);
            }
            ext.ywzj_rvp$clearPendingRadarLockEntityId();
            return;
        }
        if (!entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
            weaponUnit.setLockedEntity(target);
        }
    }

    public static Entity findManualLockCandidate(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        Set<Integer> seen = new HashSet<>();
        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 aimVec = weaponUnit.worldVec();
        Vec3 origin = weaponUnit.worldPivotPosition();
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!canSearch(radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            for (RadarUnit.DetectedObject detectedObject : radarUnit.getDetectedEntities().values()) {
                Entity entity = detectedObject.entity;
                if (entity == null || !entity.isAlive() || !seen.add(entity.getId())) {
                    continue;
                }
                Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(origin);
                if (toTarget.lengthSqr() <= 1.0E-6) {
                    continue;
                }
                double angle = Math.toDegrees(VectorUtil.angleBetween(aimVec, toTarget));
                if (Double.isNaN(angle) || angle > MANUAL_LOCK_REQUEST_FOV) {
                    continue;
                }
                double distanceScore = origin.distanceToSqr(entity.getBoundingBox().getCenter()) * 0.000001;
                double score = angle + distanceScore;
                if (score < bestScore) {
                    bestScore = score;
                    bestTarget = entity;
                }
            }
        }
        return bestTarget;
    }

    public static boolean radarCurrentlyDetects(RadarUnit radarUnit, Entity target) {
        return radarUnit != null && target != null && radarUnit.getDetectedEntities().containsKey(target.getId());
    }

    public static boolean entityMatches(Entity entity, int entityId) {
        return entity != null && entityId != Integer.MIN_VALUE && entity.getId() == entityId;
    }

    public static void setPendingRadarLock(WeaponUnit weaponUnit, int entityId) {
        if (weaponUnit instanceof WeaponUnitPendingRadarLockExt ext) {
            ext.ywzj_rvp$setPendingRadarLockEntityId(entityId);
        }
    }

    public static void clearPendingRadarLock(WeaponUnit weaponUnit) {
        if (weaponUnit instanceof WeaponUnitPendingRadarLockExt ext) {
            ext.ywzj_rvp$clearPendingRadarLockEntityId();
        }
    }

    private static void clearOtherRadarLocks(WeaponUnit weaponUnit, RadarUnit keepRadar) {
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit != keepRadar && radarUnit.getLockedEntity() != null) {
                radarUnit.setLockedEntity(null);
            }
        }
    }
}
