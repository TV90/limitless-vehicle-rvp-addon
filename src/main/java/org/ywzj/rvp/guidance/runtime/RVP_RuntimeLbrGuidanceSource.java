package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.RVP_CommandGuidanceAim;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosDesignation;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Laser Beam Riding (LBR): the missile rides along a laser beam projected from
 * the operator's weapon station. Unlike SACLOS which sends steering commands,
 * LBR computes the closest point on the beam axis to the missile (the foot of
 * the perpendicular) and guides the missile toward that point plus a forward
 * offset along the beam. This ensures the missile continuously corrects back
 * toward the beam center, making it easier to aim — the player only needs to
 * keep the crosshair on the target.
 */
public final class RVP_RuntimeLbrGuidanceSource implements RVP_RuntimeGuidanceSource {

    /** Forward offset along beam axis from the perpendicular foot (in blocks). */
    private static final double FORWARD_OFFSET = 16.0;

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.LBR;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        WeaponUnit shooterUnit = context.projectile().getShooterWeaponUnit();
        // 调用本项目武器站解析，确保 parent_weapon_unit_aim 子站使用根炮塔作为驾束起点。
        WeaponUnit aimUnit = RVP_CommandGuidanceAim.resolveOperatorAimUnit(shooterUnit);
        Vec3 beamOrigin = aimUnit != null ? aimUnit.worldPivotPosition() : Vec3.ZERO;
        // 调用本项目同步瞄准会话，以不含车体局部角瞬态的世界点建立 LBR 驾束。
        Vec3 synchronizedAimPoint = RVP_SaclosDesignation.resolveSynchronizedOperatorPoint(
                context.projectile());
        Vec3 beamDir = RVP_CommandGuidanceAim.operatorAimDirection(
                shooterUnit, beamOrigin, synchronizedAimPoint);
        if (beamDir == null || beamDir.lengthSqr() <= 1.0E-6) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.LBR);
        }

        // Compute the foot of perpendicular from missile to beam axis
        Vec3 missilePos = context.projectile().position();
        Vec3 footOfPerpendicular = closestPointOnRay(beamOrigin, beamDir, missilePos);

        // Target point: foot + forward offset along beam so missile advances
        Vec3 aimPoint = footOfPerpendicular.add(beamDir.scale(FORWARD_OFFSET));

        context.projectile().setTargetPos(aimPoint);

        // directMotion=true so WireGuidanceSteering snaps missile toward beam axis
        return RVP_GuidanceIntent.point(aimPoint, true, 1.0, RVP_EnumGuidanceType.LBR);
    }

    /**
     * Compute the closest point on a ray (origin + t*direction, t >= 0)
     * to a given point in space.
     */
    private static Vec3 closestPointOnRay(Vec3 rayOrigin, Vec3 rayDir, Vec3 point) {
        Vec3 toPoint = point.subtract(rayOrigin);
        double projection = toPoint.dot(rayDir);
        // Clamp t >= 0 so the beam only extends forward from the weapon station
        double t = Math.max(projection, 0.0);
        return rayOrigin.add(rayDir.scale(t));
    }
}
