package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.ext.WeaponUnitArmExt;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;

public final class RVP_RuntimeArmGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.ARM;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        RVP_BaseBullet projectile = context.projectile();
        if (projectile.getFlightTickCount() == 0) {
            copyPreselectedEmitter(projectile);
        }

        AntiRadiationSeekerHelper.AntiRadiationEmitter best = null;
        int interval = context.active().scanIntervalTick() != null ? context.active().scanIntervalTick() : 2;
        if (!projectile.hasAntiRadiationSignalAcquired() && projectile.getPreselectedVehicleId() >= 0) {
            interval = 1;
        }
        if (projectile.getFlightTickCount() >= projectile.getAntiRadiationNextScanTick()) {
            projectile.setAntiRadiationNextScanTick(projectile.getFlightTickCount() + interval);
            float fov = resolveArmScanHalfAngle(context);
            float range = (float) RVP_GuidanceRuntimeGeometry.resolveScanRadius(
                    context.active().targetDistanceRange());
            List<AntiRadiationSeekerHelper.AntiRadiationEmitter> emitters =
                    AntiRadiationSeekerHelper.scanVisibleEmitters(
                            projectile.level(),
                            projectile.position(),
                            projectile.getLookAngle(),
                            fov,
                            range,
                            projectile.getShooterVehicle(),
                            projectile.getFlightTickCount(),
                            projectile.getRadiationPulseTickMap(),
                            context.active().radiationPulseMemoryTick()
                    );
            best = selectEmitter(projectile, emitters, fov, range, context.active().armLockedEmitterBonus());
        }

        if (best != null) {
            projectile.setAntiRadiationLostPermanent(false);
            projectile.setAntiRadiationSignalAcquired(true);
            int memory = context.active().armMemoryTick() > 0
                    ? context.active().armMemoryTick()
                    : AntiRadiationSeekerHelper.getDefaultMemoryTick(best.radarUnit());
            projectile.setAntiRadiationMemoryLeftTick(memory);
            Vec3 aimPoint = resolveEmitterAimPoint(best);
            projectile.setTargetPos(aimPoint);
            projectile.rememberGuidancePos(aimPoint);
            return RVP_GuidanceIntent.point(aimPoint, false, 1.0, RVP_EnumGuidanceType.ARM);
        }

        if (!projectile.hasAntiRadiationSignalAcquired()
                && projectile.getPreselectedVehicleId() >= 0
                && projectile.getLastGuidancePos() != null) {
            Vec3 snapshot = projectile.getLastGuidancePos();
            projectile.setTargetPos(snapshot);
            return RVP_GuidanceIntent.point(snapshot, false, 1.0, RVP_EnumGuidanceType.ARM);
        }

        if (projectile.getAntiRadiationMemoryLeftTick() > 0 && projectile.getLastGuidancePos() != null) {
            projectile.setAntiRadiationMemoryLeftTick(projectile.getAntiRadiationMemoryLeftTick() - 1);
            Vec3 memory = projectile.getLastGuidancePos();
            projectile.setTargetPos(memory);
            return RVP_GuidanceIntent.point(memory, false, 1.0, RVP_EnumGuidanceType.ARM);
        }
        projectile.clearTarget();
        return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARM);
    }

    private static float resolveArmScanHalfAngle(RVP_GuidanceRuntimeContext context) {
        return Math.max(
                Math.max(context.active().maxLockHalfAngle(), context.active().maxGuidanceAngle()),
                0.5f
        );
    }

    private static Vec3 resolveEmitterAimPoint(AntiRadiationSeekerHelper.AntiRadiationEmitter emitter) {
        return emitter.position();
    }

    private static AntiRadiationSeekerHelper.AntiRadiationEmitter selectEmitter(
            RVP_BaseBullet projectile,
            List<AntiRadiationSeekerHelper.AntiRadiationEmitter> emitters,
            float fov,
            float range,
            float lockedBonus
    ) {
        int preselectedVehicle = projectile.getPreselectedVehicleId();
        int preselectedRadar = projectile.getPreselectedRadarIndex();
        if (preselectedVehicle >= 0) {
            for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
                if (emitter.vehicleId() == preselectedVehicle
                        && (preselectedRadar < 0 || emitter.radarIndex() == preselectedRadar)) {
                    return emitter;
                }
            }
        }

        AntiRadiationSeekerHelper.AntiRadiationEmitter best = null;
        double bestScore = Double.MAX_VALUE;
        for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
            double score = AntiRadiationSeekerHelper.score(
                    projectile.position(),
                    projectile.getLookAngle(),
                    fov,
                    range,
                    emitter.pdw(),
                    lockedBonus
            );
            if (score < bestScore) {
                bestScore = score;
                best = emitter;
            }
        }
        return best;
    }

    private static void copyPreselectedEmitter(RVP_BaseBullet projectile) {
        WeaponUnit unit = projectile.getShooterWeaponUnit();
        if (unit == null) {
            return;
        }
        WeaponUnit root = unit.getRootParentWeaponUnit();
        if (!(root instanceof WeaponUnitArmExt arm)) {
            return;
        }
        int vehicleId = arm.ywzj_rvp$getArmPreselectedVehicleId();
        int radarIndex = arm.ywzj_rvp$getArmPreselectedRadarIndex();
        projectile.setPreselectedTarget(vehicleId, radarIndex);
        Vec3 position = arm.ywzj_rvp$getArmPreselectedPos();
        if (vehicleId >= 0 && position != null) {
            projectile.setAntiRadiationSignalAcquired(false);
            projectile.setTargetPos(position);
            projectile.rememberGuidancePos(position);
        }
    }
}
