package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

final class RVP_RuntimeSeekerSupport {

    private RVP_RuntimeSeekerSupport() {}

    @Nullable
    static Entity validateEntity(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_EnumGuidanceType type,
            RVP_GuidanceActiveConfig config
    ) {
        if (!RVP_GuidanceRuntimeGeometry.passesTrackLimits(projectile, target, config)) {
            return null;
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(projectile, target, type, config);
        if (result.intercepted()) {
            projectile.discard();
            return null;
        }
        if (result.decoyed()) {
            Entity decoy = RVP_CountermeasureState.findDecoyTarget(target, 16.0).orElse(null);
            if (decoy == null) {
                return null;
            }
            projectile.setTargetEntity(decoy);
            return decoy;
        }
        return result.isDenied() ? null : target;
    }

    @Nullable
    static Entity scanRadarTarget(RVP_BaseBullet projectile, RVP_GuidanceActiveConfig config) {
        double range = RVP_GuidanceRuntimeGeometry.resolveScanRadius(config.targetDistanceRange());
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : Radar.scanTargets(projectile, projectile.position(), range,
                pos -> RVP_GuidanceRuntimeGeometry.withinAngle(
                        projectile.getLookAngle(),
                        pos.subtract(projectile.position()),
                        config.maxLockHalfAngle()))) {
            if (entity == projectile.getShooterVehicle() || !isRadarScannable(entity)) {
                continue;
            }
            if (!RVP_GuidanceRuntimeGeometry.passesAcquireLimits(projectile, entity, config)) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(projectile.position());
            double angle = org.ywzj.rvp.guidance.RVP_GuidanceSeekerUtil.angleBetween(
                    projectile.getLookAngle(), toTarget);
            double score = angle * 4.0 + projectile.distanceTo(entity) / Math.max(range, 1.0);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    private static boolean isRadarScannable(Entity entity) {
        return entity instanceof AbstractVehicle && entity.isAlive()
                || RVP_RadarContactHelper.isHbmMissile(entity);
    }
}
