package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

/**
 * Countermeasure checks shared by guidance compositor paths.
 */
public final class RVP_GuidanceCountermeasures {

    private RVP_GuidanceCountermeasures() {}

    public static RVP_EnumCounterDecision apply(
            RVP_BaseBullet projectile,
            RVP_EnumGuidanceType type,
            RVP_GuidanceContext context,
            RVP_GuidanceData.Source source
    ) {
        Entity target = projectile.getTargetEntity();
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(
                projectile, target, type, context.effective().seeker());
        if (result.intercepted()) {
            projectile.discard();
            return RVP_EnumCounterDecision.DISCARD;
        }
        if (result.decoyed()) {
            RVP_CountermeasureState.findDecoyTarget(target, 16.0).ifPresent(projectile::setTargetEntity);
            return RVP_EnumCounterDecision.CLEAR;
        }
        if (result.isDenied()) {
            return source.isFallbackOnJammed()
                    ? RVP_EnumCounterDecision.SKIP_SOURCE
                    : RVP_EnumCounterDecision.STOP_STAGE;
        }
        return RVP_EnumCounterDecision.CLEAR;
    }
}
