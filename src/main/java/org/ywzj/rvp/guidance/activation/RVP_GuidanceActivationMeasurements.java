package org.ywzj.rvp.guidance.activation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_GuidanceSeekerUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Shared distance/altitude sampling for activation strategies.
 */
public final class RVP_GuidanceActivationMeasurements {

    private RVP_GuidanceActivationMeasurements() {}

    public static double targetPointDistance(RVP_BaseBullet projectile) {
        Entity targetEntity = projectile.getTargetEntity();
        if (targetEntity != null && targetEntity.isAlive()) {
            return projectile.distanceTo(targetEntity);
        }
        Vec3 targetPos = projectile.getTargetPos();
        if (targetPos != null) {
            return projectile.position().distanceTo(targetPos);
        }
        Vec3 last = projectile.getLastGuidancePos();
        if (last != null) {
            return projectile.position().distanceTo(last);
        }
        return -1;
    }

    public static double altitudeAgl(RVP_BaseBullet projectile) {
        int groundY = projectile.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                projectile.getBlockX(),
                projectile.getBlockZ()
        );
        return projectile.getY() - groundY;
    }

    public static boolean hasIllumination(RVP_BaseBullet projectile) {
        return RVP_GuidanceSeekerUtil.getIlluminatedTarget(projectile) != null;
    }
}
