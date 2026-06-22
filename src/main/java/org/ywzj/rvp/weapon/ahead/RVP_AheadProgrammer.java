package org.ywzj.rvp.weapon.ahead;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.debug.RVP_AheadDebug;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

public final class RVP_AheadProgrammer {
    private RVP_AheadProgrammer() {}

    public static boolean isAheadWeapon(@Nullable RVP_WeaponData data) {
        return data != null
                && data.getWeaponKind() == RVP_EnumWeaponKind.MACHINEGUN
                && data.isAheadEnabled()
                && data.getFuseData().isProgrammableAirburst();
    }

    public static RVP_AheadSolution solve(RVP_WeaponData data, WeaponUnit weaponUnit, AimContext aim,
                                          float partialTick) {
        if (!isAheadWeapon(data)) {
            return RVP_AheadSolution.invalid("not_ahead_weapon");
        }
        if (weaponUnit == null || aim == null) {
            return RVP_AheadSolution.invalid("missing_context");
        }
        Vec3 muzzle = RVP_AimContexts.muzzle(aim);
        if (muzzle == null || muzzle == Vec3.ZERO) {
            return RVP_AheadSolution.invalid("missing_muzzle");
        }

        Entity target = RVP_MachinegunLeadSolver.resolveTrackedTarget(weaponUnit);
        if (target != null) {
            RVP_LeadSolution lead = RVP_MachinegunLeadSolver.solveForTarget(
                    weaponUnit, data, muzzle, target, partialTick
            );
            if (lead != null && lead.leadWorldPos() != null) {
                return fromReference(data, lead.leadWorldPos(), muzzle, true);
            }
        }

        if (data.isAheadRequireLock()) {
            return RVP_AheadSolution.invalid("lock_required");
        }

        Vec3 impact = RVP_AimContexts.impactPoint(aim);
        if (impact == null) {
            return RVP_AheadSolution.invalid("missing_impact_point");
        }
        return fromReference(data, impact, muzzle, false);
    }

    public static RVP_AheadSolution programForShot(AbstractVehicle vehicle, WeaponUnit weaponUnit, int weaponIndex,
                                                   RVP_WeaponData data, AimContext aim, float partialTick) {
        if (!isAheadWeapon(data) || vehicle == null || weaponUnit == null) {
            return RVP_AheadSolution.invalid("skip");
        }
        RVP_AheadSolution solution = solve(data, weaponUnit, aim, partialTick);
        RVP_AirburstRangeStore.set(vehicle, weaponUnit, weaponIndex, solution.programmedDistanceMeters());
        RVP_AheadDebug.logProgram(data, solution);
        return solution;
    }

    private static RVP_AheadSolution fromReference(RVP_WeaponData data, Vec3 referenceWorldPos, Vec3 muzzle,
                                                   boolean usedLeadSolution) {
        double referenceDistance = muzzle.distanceTo(referenceWorldPos);
        int programmedDistance = Mth.floor(referenceDistance - data.getAheadBurstOffsetMeters() + 0.5D);
        int min = data.getFuseData().getAirburstMeasureMin();
        int max = data.getFuseData().getAirburstMeasureMax();
        if (programmedDistance <= min || programmedDistance >= max) {
            return RVP_AheadSolution.invalid("out_of_fuse_range");
        }
        return new RVP_AheadSolution(
                referenceWorldPos,
                referenceDistance,
                programmedDistance,
                usedLeadSolution,
                null
        );
    }
}
