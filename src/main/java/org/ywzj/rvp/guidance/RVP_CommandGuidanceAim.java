package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** Shared operator aim resolution for SACLOS and HITL command guidance. */
public final class RVP_CommandGuidanceAim {

    private RVP_CommandGuidanceAim() {}

    @Nullable
    public static Vec3 operatorAimDirection(@Nullable WeaponUnit shooterUnit) {
        WeaponUnit aimUnit = resolveOperatorAimUnit(shooterUnit);
        if (aimUnit == null) {
            return null;
        }
        Vec3 direction = aimUnit.worldVec(aimUnit.getXAimRot(), aimUnit.getYAimRot());
        if (direction.lengthSqr() <= 1.0E-6) {
            direction = aimUnit.worldVec();
        }
        return direction.lengthSqr() > 1.0E-6 ? direction.normalize() : null;
    }

    @Nullable
    public static WeaponUnit resolveOperatorAimUnit(@Nullable WeaponUnit shooterUnit) {
        if (shooterUnit == null) {
            return null;
        }
        return shooterUnit.isParentWeaponUnitAim()
                ? shooterUnit.getRootParentWeaponUnit()
                : shooterUnit;
    }
}
