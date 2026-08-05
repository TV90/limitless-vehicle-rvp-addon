package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_CommandGuidanceAim;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * SACLOS guidance source with optional semi-active correction.
 *
 * <p>When {@code semiCorrectionEnabled} is false, behaves like original SACLOS:
 * the missile flies toward a point on the operator's line of sight at scan radius distance.
 *
 * <p>When {@code semiCorrectionEnabled} is true, copies LBR beam-riding logic as the base
 * (target = foot of perpendicular + forward offset along LOS), then adds a separate
 * underdamped spring-mass-damper oscillator on top.
 *
 * <p>The oscillator operates in **world-space 3D vectors** (not per-tick perp basis),
 * to prevent the perp basis from rotating with LOS and causing circular motion.
 *
 * <p>This produces the desired behavior:
 * <ul>
 *   <li>LOS stable: missile rides the beam like LBR, with small noise-driven wobble</li>
 *   <li>LOS rotates: delta_phys kicks the oscillator → overshoot → oscillation → decay</li>
 * </ul>
 */
public final class RVP_RuntimeSaclosGuidanceSource implements RVP_RuntimeGuidanceSource {

    /** Forward offset along LOS from the perpendicular foot, matching LBR. */
    private static final double FORWARD_OFFSET = 32.0;

    /** Maximum perpendicular offset clamp (blocks). */
    private static final double MAX_OFFSET = 30.0;

    /** Noise refresh interval (ticks). */
    private static final int NOISE_INTERVAL = 10;

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.SACLOS;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        RVP_BaseBullet projectile = context.projectile();
        RVP_GuidanceActiveConfig active = context.active();

        Vec3 direction = RVP_CommandGuidanceAim.operatorAimDirection(
                projectile.getShooterWeaponUnit());
        if (direction == null || direction.lengthSqr() <= 1.0E-6) {
            resetSaclosState(projectile);
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
        }
        Vec3 dir = direction.normalize();

        // Original SACLOS behavior when semi-correction is disabled
        if (!active.semiCorrectionEnabled()) {
            double range = RVP_GuidanceRuntimeGeometry.resolveScanRadius(
                    active.targetDistanceRange());
            Vec3 point = projectile.position().add(dir.scale(range));
            projectile.setTargetPos(point);
            return RVP_GuidanceIntent.point(point, true, 1.0, RVP_EnumGuidanceType.SACLOS);
        }

        // === Semi-correction enabled: LBR base + spring-damper oscillator ===

        // -- LBR base logic (copied from RVP_RuntimeLbrGuidanceSource) --
        WeaponUnit shooterUnit = projectile.getShooterWeaponUnit();
        Vec3 beamOrigin = shooterUnit != null ? shooterUnit.worldPivotPosition() : Vec3.ZERO;
        Vec3 missilePos = projectile.position();

        // Foot of perpendicular from missile to LOS ray
        Vec3 foot = closestPointOnRay(beamOrigin, dir, missilePos);

        // LBR base target: foot + forward offset along LOS
        Vec3 baseTarget = foot.add(dir.scale(FORWARD_OFFSET));

        // -- Spring-damper oscillator (world-space 3D vectors) --
        // Physical perpendicular displacement of missile from LOS (world space)
        Vec3 toMissile = missilePos.subtract(foot);
        Vec3 perpComponent = toMissile.subtract(dir.scale(toMissile.dot(dir)));

        // Delta of physical offset since last tick (perturbation from LOS movement)
        Vec3 deltaPhys = perpComponent.subtract(projectile.saclosLastPhysVec);
        projectile.saclosLastPhysVec = perpComponent;

        double stiffness = active.semiCorrectionStiffness();
        double damping = active.semiCorrectionDamping();
        double wobble = active.semiCorrectionWobble();

        // Noise for steady-state wobble (random direction in world space)
        Vec3 noise = Vec3.ZERO;
        if (wobble > 0.0 && projectile.getFlightTickCount() % NOISE_INTERVAL == 0) {
            noise = randomPerpVector(dir, projectile, wobble);
        }

        // Free oscillator in world space:
        //   spring pulls offset toward zero
        //   damping resists velocity
        //   delta_phys kicks (from LOS rotation)
        //   noise adds steady-state wobble
        Vec3 springForce = projectile.saclosOffsetVec.scale(-stiffness);
        Vec3 dampingForce = projectile.saclosVelVec.scale(-damping);
        Vec3 accel = springForce.add(dampingForce).add(deltaPhys).add(noise);

        projectile.saclosVelVec = projectile.saclosVelVec.add(accel);
        projectile.saclosOffsetVec = projectile.saclosOffsetVec.add(projectile.saclosVelVec);

        // Clamp offset magnitude
        double offsetMag = projectile.saclosOffsetVec.length();
        if (offsetMag > MAX_OFFSET) {
            double scale = MAX_OFFSET / offsetMag;
            projectile.saclosOffsetVec = projectile.saclosOffsetVec.scale(scale);
        }

        // Final target: LBR base + oscillation offset (world space, no perp basis rotation)
        Vec3 finalTarget = baseTarget.add(projectile.saclosOffsetVec);

        projectile.setTargetPos(finalTarget);
        return RVP_GuidanceIntent.point(finalTarget, true, 1.0, RVP_EnumGuidanceType.SACLOS);
    }

    private static void resetSaclosState(RVP_BaseBullet projectile) {
        projectile.saclosOffsetVec = Vec3.ZERO;
        projectile.saclosVelVec = Vec3.ZERO;
        projectile.saclosLastPhysVec = Vec3.ZERO;
    }

    /**
     * Generate a random vector perpendicular to {@code dir} with magnitude up to {@code wobble}.
     */
    private static Vec3 randomPerpVector(Vec3 dir, RVP_BaseBullet projectile, double wobble) {
        Vec3 perp1 = buildPerp1(dir);
        Vec3 perp2 = dir.cross(perp1).normalize();
        double a = (projectile.level().random.nextDouble() - 0.5) * 2.0 * wobble;
        double b = (projectile.level().random.nextDouble() - 0.5) * 2.0 * wobble;
        return perp1.scale(a).add(perp2.scale(b));
    }

    private static Vec3 buildPerp1(Vec3 dir) {
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        Vec3 perp1 = dir.cross(up);
        if (perp1.lengthSqr() < 1.0E-8) {
            perp1 = new Vec3(1.0, 0.0, 0.0);
        }
        return perp1.normalize();
    }

    private static Vec3 closestPointOnRay(Vec3 rayOrigin, Vec3 rayDir, Vec3 point) {
        Vec3 toPoint = point.subtract(rayOrigin);
        double t = Math.max(toPoint.dot(rayDir), 0.0);
        return rayOrigin.add(rayDir.scale(t));
    }
}
