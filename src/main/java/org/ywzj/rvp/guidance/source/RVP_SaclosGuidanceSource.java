package org.ywzj.rvp.guidance.source;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosDesignation;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

/**
 * Semi-active laser beam-riding: tracks live designation point while operator laser is on.
 */
public final class RVP_SaclosGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.SACLOS;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        RVP_BaseBullet projectile = context.projectile();

        if (!RVP_SaclosOperatorSession.isLaserEnabled(projectile)
                && !isHitlDesignate(projectile)) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
        }

        Entity target = projectile.getTargetEntity();
        if (target != null && target.isAlive()) {
            Vec3 aimPoint = target.getBoundingBox().getCenter();
            if (isDenied(projectile, aimPoint, source, context)) {
                projectile.clearTarget();
                return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
            }
            projectile.rememberGuidancePos(aimPoint);
            return RVP_GuidanceIntent.entity(target, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.SACLOS);
        }

        Vec3 point = RVP_SaclosDesignation.resolveDesignationPoint(projectile);
        if (point == null) {
            point = projectile.getTargetPos();
        }
        if (point == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
        }
        if (isDenied(projectile, point, source, context)) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
        }
        projectile.setTargetPos(point);
        return RVP_GuidanceIntent.point(point, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.SACLOS);
    }

    private static boolean isHitlDesignate(RVP_BaseBullet projectile) {
        return projectile instanceof RVP_MissileEntity missile
                && missile.rvp$isHitlActive()
                && missile.rvp$getHitlControlMode() == RVP_EnumHitlControlMode.DESIGNATE;
    }

    private static boolean isDenied(
            RVP_BaseBullet projectile,
            Vec3 point,
            RVP_GuidanceData.Source source,
            RVP_GuidanceContext context
    ) {
        if (source.isTakeOverMotion()) {
            return false;
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(
                projectile, point, RVP_EnumGuidanceType.SACLOS, context.effective().seeker());
        return result.isDenied();
    }
}
