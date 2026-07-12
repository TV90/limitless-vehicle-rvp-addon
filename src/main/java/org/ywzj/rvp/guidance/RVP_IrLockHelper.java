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
        return isTargetWithinLimits(
                weaponUnit,
                target,
                halfAngleFromFull(data.getMaxLockOnAngle()) + Math.max(0f, extraAngleMargin),
                Math.max(0f, data.getMaxLockOnRange()),
                data.getLockMinHeight()
        );
    }

    public static boolean isTargetWithinHoldLimits(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        return isTargetWithinLimits(
                weaponUnit,
                target,
                Math.max(1f, data.getMaxGuideHeadAngle()),
                Math.max(0f, data.getMaxLockOnRange()),
                data.getLockMinHeight()
        );
    }

    public static boolean isTargetWithinLimits(
            WeaponUnit weaponUnit,
            Entity target,
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

        Vec3 aim = weaponUnit.worldVec();
        if (aim.lengthSqr() > 1.0E-6) {
            double dot = Mth.clamp(aim.normalize().dot(toTarget.normalize()), -1.0, 1.0);
            double degree = Math.toDegrees(Math.acos(dot));
            if (degree > maxAngleDeg) {
                return false;
            }
        }

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

    public static boolean usesIrAcquireOnEo(WeaponUnitData.FireControlSensorType sensorType, RVP_WeaponData data) {
        return sensorType == WeaponUnitData.FireControlSensorType.EO
                && isIrLaunchWeapon(data)
                && !data.isEnableHms();
    }
}
