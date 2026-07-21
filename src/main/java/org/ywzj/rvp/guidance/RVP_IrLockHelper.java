package org.ywzj.rvp.guidance;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.api.entity.TargetObstruction;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public final class RVP_IrLockHelper {

    private RVP_IrLockHelper() {}

    public static float halfAngleFromFull(float fullAngleDeg) {
        return Math.max(0.5f, Math.max(0f, fullAngleDeg) * 0.5f);
    }

    public static boolean isIrLaunchWeapon(@Nullable RVP_WeaponData data) {
        return data != null
                && data.isHomingProjectile()
                && data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                && !data.isRadarHoming()
                && !data.isAntiRadiationMissile()
                && !data.isGpsMissile();
    }

    public static boolean isTargetWithinAcquireLimits(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        return isTargetWithinAcquireLimits(weaponUnit, target, data, 0f);
    }

    public static boolean isTargetWithinAcquireLimits(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data, float extraAngleMargin) {
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        return isTargetWithinRangedLimits(
                weaponUnit, target, null,
                launch.maxLockHalfAngle() + Math.max(0f, extraAngleMargin),
                launch.targetDistanceRange(), launch.altitudeRange());
    }

    public static boolean isTargetWithinHoldLimits(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        return isTargetWithinRangedLimits(
                weaponUnit, target, null,
                launch.maxOffAxisLockAngle(), launch.targetDistanceRange(), launch.altitudeRange());
    }

    /**
     * Checks the persistent part of an IR lock without applying the off-axis gate.
     * HMD code applies its own grace period to the angular gate, but must never let
     * that grace period extend range, altitude, or line-of-sight validity.
     */
    public static boolean isTargetWithinHoldEnvelope(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        return isTargetWithinRangedLimits(
                weaponUnit, target, null, 180f,
                launch.targetDistanceRange(), launch.altitudeRange());
    }

    public static boolean isTargetWithinLimits(
            WeaponUnit weaponUnit,
            Entity target,
            float maxAngleDeg,
            float maxRange,
            float lockMinHeight
    ) {
        return isTargetWithinLimits(weaponUnit, target, null, maxAngleDeg, maxRange, lockMinHeight);
    }

    public static boolean isTargetWithinLimits(
            WeaponUnit weaponUnit,
            Entity target,
            @Nullable Vec3 referenceDir,
            float maxAngleDeg,
            float maxRange,
            float lockMinHeight
    ) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        if (!RVP_GuidanceMath.isTargetPassAltFilter(target, lockMinHeight)) {
            return false;
        }

        Vec3 checkStart = weaponUnit.worldPivotPosition();
        Vec3 checkEnd = target.getBoundingBox().getCenter();
        Vec3 toTarget = checkEnd.subtract(checkStart);
        if (toTarget.lengthSqr() <= 1.0E-6) {
            return true;
        }

        if (maxRange > 0f && toTarget.lengthSqr() > maxRange * maxRange) {
            return false;
        }

        Vec3 aim = referenceDir != null ? referenceDir : weaponUnit.worldVec();
        if (aim.lengthSqr() > 1.0E-6) {
            double dot = Mth.clamp(aim.normalize().dot(toTarget.normalize()), -1.0, 1.0);
            double degree = Math.toDegrees(Math.acos(dot));
            if (degree > maxAngleDeg) {
                return false;
            }
        }

        return hasClearLineOfSight(weaponUnit, target, checkStart, checkEnd);
    }

    public static boolean isTargetWithinAcquireLimits(
            WeaponUnit weaponUnit,
            Entity target,
            RVP_WeaponData data,
            @Nullable Vec3 referenceDir
    ) {
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        return isTargetWithinRangedLimits(
                weaponUnit, target, referenceDir, launch.maxLockHalfAngle(),
                launch.targetDistanceRange(), launch.altitudeRange());
    }

    public static boolean isTargetWithinLaunchAltitudeRange(RVP_WeaponData data, Entity target) {
        if (data == null || target == null || !target.isAlive()) {
            return false;
        }
        return containsAltitude(RVP_GuidanceModelResolver.resolveLaunch(data).altitudeRange(), target);
    }

    public static RVP_Range<Float> getLaunchAltitudeRange(RVP_WeaponData data) {
        return data == null ? null : RVP_GuidanceModelResolver.resolveLaunch(data).altitudeRange();
    }

    private static boolean isTargetWithinRangedLimits(
            WeaponUnit weaponUnit,
            Entity target,
            @Nullable Vec3 referenceDir,
            float maxAngleDeg,
            @Nullable RVP_Range<Float> distanceRange,
            @Nullable RVP_Range<Float> altitudeRange
    ) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        Vec3 checkStart = weaponUnit.worldPivotPosition();
        Vec3 checkEnd = target.getBoundingBox().getCenter();
        Vec3 toTarget = checkEnd.subtract(checkStart);
        if (toTarget.lengthSqr() <= 1.0E-6) {
            return containsAltitude(altitudeRange, target);
        }
        double distance = toTarget.length();
        if (distanceRange != null && !distanceRange.contains((float) distance)) {
            return false;
        }
        if (!containsAltitude(altitudeRange, target)) {
            return false;
        }
        Vec3 aim = referenceDir != null ? referenceDir : weaponUnit.worldVec();
        if (aim.lengthSqr() > 1.0E-6) {
            double dot = Mth.clamp(aim.normalize().dot(toTarget.normalize()), -1.0, 1.0);
            if (Math.toDegrees(Math.acos(dot)) > Math.max(maxAngleDeg, 0f)) {
                return false;
            }
        }
        return hasClearLineOfSight(weaponUnit, target, checkStart, checkEnd);
    }

    private static boolean containsAltitude(@Nullable RVP_Range<Float> altitudeRange, Entity target) {
        if (altitudeRange == null) {
            return true;
        }
        int groundY = target.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(target.getX()), Mth.floor(target.getZ()));
        return altitudeRange.contains((float) (target.getY() - groundY));
    }

    private static boolean hasClearLineOfSight(
            WeaponUnit weaponUnit,
            Entity target,
            Vec3 checkStart,
            Vec3 checkEnd
    ) {
        Level level = target.level();
        BlockHitResult result = level.clip(new ClipContext(
                checkStart,
                checkEnd,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                weaponUnit.getVehicle()
        ));
        if (result.getType() != HitResult.Type.MISS) {
            BlockPos pos = result.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() && state.canOcclude()) {
                return false;
            }
        }

        EntityHitResult entityHit = VectorUtil.hitEntity(weaponUnit.getVehicle(), checkStart, checkEnd);
        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof SightObstruction) {
                return false;
            }
            if (entity instanceof TargetObstruction && entity != target && !(entity instanceof PartEntity<?>)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    public static Entity resolveUsableIrTarget(WeaponUnit weaponUnit, @Nullable Entity primary, @Nullable Entity fallback, RVP_WeaponData data) {
        if (primary != null && isTargetWithinAcquireLimits(weaponUnit, primary, data)) {
            return primary;
        }
        if (fallback != null && isTargetWithinAcquireLimits(weaponUnit, fallback, data)) {
            return fallback;
        }
        return null;
    }

    @Nullable
    public static Entity resolveUsableIrLaunchTarget(WeaponUnit weaponUnit, @Nullable Entity primary, @Nullable Entity fallback, RVP_WeaponData data) {
        if (primary != null && isTargetWithinHoldLimits(weaponUnit, primary, data)) {
            return primary;
        }
        if (fallback != null && isTargetWithinAcquireLimits(weaponUnit, fallback, data)) {
            return fallback;
        }
        return null;
    }

    public static Vec3 resolveIrBoresightDir(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return Vec3.ZERO;
        }
        Vec3 boresight = weaponUnit.worldVec(0f, 0f);
        if (boresight.lengthSqr() <= 1.0E-6) {
            boresight = weaponUnit.worldVec();
        }
        return boresight.lengthSqr() <= 1.0E-6 ? Vec3.ZERO : boresight.normalize();
    }

    public static boolean usesIrAcquireOnEo(WeaponUnitData.FireControlSensorType sensorType, RVP_WeaponData data) {
        return sensorType == WeaponUnitData.FireControlSensorType.EO
                && isIrLaunchWeapon(data)
                && !data.isEnableIrHmd();
    }
}
