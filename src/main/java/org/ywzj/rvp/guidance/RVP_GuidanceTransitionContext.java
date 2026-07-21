package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

public record RVP_GuidanceTransitionContext(
        int tick,
        double targetDistance,
        double targetHorizontalDistance
) {
    public static RVP_GuidanceTransitionContext from(RVP_BaseBullet projectile) {
        Vec3 target = resolveTarget(projectile);
        if (target == null) {
            return new RVP_GuidanceTransitionContext(projectile.tickCount, -1, -1);
        }
        Vec3 offset = target.subtract(projectile.position());
        return new RVP_GuidanceTransitionContext(
                projectile.tickCount,
                offset.length(),
                Math.sqrt(offset.x * offset.x + offset.z * offset.z)
        );
    }

    private static Vec3 resolveTarget(RVP_BaseBullet projectile) {
        Entity entity = projectile.getTargetEntity();
        if (entity != null && entity.isAlive()) {
            return entity.getBoundingBox().getCenter();
        }
        if (projectile.getTargetPos() != null) {
            return projectile.getTargetPos();
        }
        return projectile.getLastGuidancePos();
    }
}
