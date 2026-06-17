package org.ywzj.rvp.guidance.source;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSourceParamsData;

import java.util.List;

public final class RVP_ArmGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.ARM;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        RVP_BaseBullet projectile = context.projectile();
        RVP_GuidanceSourceParamsData params = source.getParams();
        boolean allowReacquire = params.reacquire(true);
        boolean canScan = !projectile.isAntiRadiationLostPermanent() || allowReacquire;
        AntiRadiationSeekerHelper.AntiRadiationEmitter best = null;

        if (canScan && projectile.tickCount >= projectile.getAntiRadiationNextScanTick()) {
            projectile.setAntiRadiationNextScanTick(
                    projectile.tickCount + params.scanIntervalTick(2));
            List<AntiRadiationSeekerHelper.AntiRadiationEmitter> emitters = AntiRadiationSeekerHelper.scanVisibleEmitters(
                    projectile.level(),
                    projectile.position(),
                    projectile.getLookAngle(),
                    context.effective().seeker().getFov(),
                    context.effective().seeker().getRange(),
                    projectile.getShooterVehicle(),
                    projectile.tickCount,
                    projectile.getRadiationPulseTickMap(),
                    params.radiationPulseMemoryTick(25)
            );

            // STEP 1: If the projectile has a preselected target, try to find it first
            int preselectVehicleId = projectile.getPreselectedVehicleId();
            int preselectRadarIndex = projectile.getPreselectedRadarIndex();
            if (preselectVehicleId >= 0) {
                for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
                    if (emitter.vehicleId() == preselectVehicleId
                            && (preselectRadarIndex < 0
                            || emitter.radarIndex() == preselectRadarIndex)) {
                        best = emitter;
                        break;
                    }
                }
            }

            // STEP 2: If no preselected match (or no preselect), fall back to best score
            if (best == null) {
                double bestScore = Double.MAX_VALUE;
                for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
                    double score = AntiRadiationSeekerHelper.score(
                            projectile.position(),
                            projectile.getLookAngle(),
                            context.effective().seeker().getFov(),
                            context.effective().seeker().getRange(),
                            emitter.pdw(),
                            params.lockedBonus(0.5f));
                    if (score < bestScore) {
                        bestScore = score;
                        best = emitter;
                    }
                }
            }
        }

        if (best != null) {
            projectile.setAntiRadiationLostPermanent(false);
            int configuredMemory = params.memoryTick(0);
            int memoryTick = configuredMemory > 0
                    ? configuredMemory
                    : AntiRadiationSeekerHelper.getDefaultMemoryTick(best.radarUnit());
            projectile.setAntiRadiationMemoryLeftTick(memoryTick);
            projectile.setTargetPos(best.position());
            return RVP_GuidanceIntent.point(best.position(), source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.ARM);
        }
        if (projectile.getAntiRadiationMemoryLeftTick() > 0 && projectile.getLastGuidancePos() != null) {
            projectile.setAntiRadiationMemoryLeftTick(projectile.getAntiRadiationMemoryLeftTick() - 1);
            Vec3 memory = projectile.getLastGuidancePos();
            projectile.setTargetPos(memory);
            return RVP_GuidanceIntent.point(memory, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.ARM);
        }
        projectile.clearTarget();
        if (!allowReacquire) {
            projectile.setAntiRadiationLostPermanent(true);
        }
        return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARM);
    }
}
