package org.ywzj.rvp.guidance.activation;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/**
 * Per-tick measurements used by activation strategies.
 */
public record RVP_GuidanceActivationContext(
        RVP_BaseBullet projectile,
        int tick,
        double targetPointDistance,
        double entityDistance,
        double altitudeAgl,
        boolean hasTargetPoint,
        boolean hasEntityTarget,
        boolean hasIllumination
) {
    public static RVP_GuidanceActivationContext from(RVP_BaseBullet projectile) {
        Entity entity = projectile.getTargetEntity();
        boolean aliveEntity = entity != null && entity.isAlive();
        double entityDist = aliveEntity ? projectile.distanceTo(entity) : -1;
        double pointDist = RVP_GuidanceActivationMeasurements.targetPointDistance(projectile);
        return new RVP_GuidanceActivationContext(
                projectile,
                projectile.tickCount,
                pointDist,
                entityDist,
                RVP_GuidanceActivationMeasurements.altitudeAgl(projectile),
                pointDist >= 0,
                aliveEntity,
                RVP_GuidanceActivationMeasurements.hasIllumination(projectile)
        );
    }

    @Nullable
    public Entity targetEntity() {
        return projectile.getTargetEntity();
    }
}
