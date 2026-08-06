package org.ywzj.rvp.radar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RVP_RadarRoleHelper {
    public static final String ROLE_ALL = "ALL";
    public static final String ROLE_SEARCH = "SEARCH";
    public static final String ROLE_FIRE_CONTROL = "FIRE_CONTROL";
    public static final float MANUAL_LOCK_REQUEST_FOV = 18.0f;

    private RVP_RadarRoleHelper() {}

    public record ManualLockCandidate(Entity entity, Vec3 position, double score) {}

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

    @Nullable
    public static Entity getEffectiveRfLockedEntity(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        RadarUnit radarUnit = getLockedRadar(root);
        if (radarUnit != null) {
            Entity radarLocked = radarUnit.getLockedEntity();
            if (radarLocked != null && radarLocked.isAlive()) {
                return radarLocked;
            }
        }
        int externalLockedId = RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root);
        if (externalLockedId != Integer.MIN_VALUE) {
            Entity externalLocked = root.getVehicle().level().getEntity(externalLockedId);
            if (externalLocked != null && externalLocked.isAlive()) {
                return externalLocked;
            }
        }
        Entity localLocked = root.getLockedEntity();
        return localLocked != null && localLocked.isAlive() ? localLocked : null;
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
        int pendingId = RVP_WeaponLockStateTable.getPendingRadarLockEntityId(weaponUnit);
        if (pendingId == Integer.MIN_VALUE) {
            return;
        }
        RadarUnit lockRadar = getPreferredLockRadar(weaponUnit);
        if (lockRadar == null) {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
            return;
        }
        Entity target = weaponUnit.getVehicle().level().getEntity(pendingId);
        if (target == null || !target.isAlive()) {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
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
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
            return;
        }
        if (!entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
            weaponUnit.setLockedEntity(target);
        }
    }

    public static Vec3 resolveManualLockAimVec(@Nullable WeaponUnit weaponUnit) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null) {
            Vec3 look = player.getLookAngle();
            if (look.lengthSqr() > 1.0E-6) {
                return look.normalize();
            }
        }
        if (weaponUnit != null) {
            Vec3 aimVec = weaponUnit.worldVec();
            if (aimVec.lengthSqr() > 1.0E-6) {
                return aimVec.normalize();
            }
        }
        return new Vec3(0.0, 0.0, 1.0);
    }

    public static double scoreManualLockCandidate(Vec3 origin, Vec3 aimVec, Vec3 targetPos) {
        Vec3 toTarget = targetPos.subtract(origin);
        if (toTarget.lengthSqr() <= 1.0E-6) {
            return Double.MAX_VALUE;
        }
        double angle = Math.toDegrees(VectorUtil.angleBetween(aimVec, toTarget));
        if (Double.isNaN(angle)) {
            return Double.MAX_VALUE;
        }
        double distance = Math.sqrt(origin.distanceToSqr(targetPos));
        return angle * 4.0 + distance * 0.01;
    }

    public static List<ManualLockCandidate> collectManualLockCandidates(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return List.of();
        }
        Set<Integer> seen = new HashSet<>();
        List<ManualLockCandidate> candidates = new ArrayList<>();
        Vec3 aimVec = resolveManualLockAimVec(weaponUnit);
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
                Vec3 targetPos = entity.getBoundingBox().getCenter();
                double score = scoreManualLockCandidate(origin, aimVec, targetPos);
                if (!Double.isFinite(score)) {
                    continue;
                }
                candidates.add(new ManualLockCandidate(entity, targetPos, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(ManualLockCandidate::score));
        return candidates;
    }

    public static Entity findManualLockCandidate(WeaponUnit weaponUnit) {
        List<ManualLockCandidate> candidates = collectManualLockCandidates(weaponUnit);
        return candidates.isEmpty() ? null : candidates.get(0).entity();
    }

    public static boolean radarCurrentlyDetects(RadarUnit radarUnit, Entity target) {
        return radarUnit != null && target != null && radarUnit.getDetectedEntities().containsKey(target.getId());
    }

    public static boolean entityMatches(Entity entity, int entityId) {
        return entity != null && entityId != Integer.MIN_VALUE && entity.getId() == entityId;
    }

    public static void setPendingRadarLock(WeaponUnit weaponUnit, int entityId) {
        if (weaponUnit != null) {
            RVP_WeaponLockStateTable.setPendingRadarLockEntityId(weaponUnit, entityId);
        }
    }

    public static void clearPendingRadarLock(WeaponUnit weaponUnit) {
        if (weaponUnit != null) {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
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
