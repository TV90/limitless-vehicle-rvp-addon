package org.ywzj.rvp.guidance.source;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSeekerUtil;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

public final class RVP_SarhGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.SARH;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        RVP_BaseBullet projectile = context.projectile();
        if (source.getParams().requireIllumination(true)) {
            Entity illuminated = RVP_GuidanceSeekerUtil.getIlluminatedTarget(projectile);
            if (illuminated == null) {
                projectile.clearTarget();
                return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SARH);
            }
            projectile.setTargetEntity(illuminated);
        }
        return RVP_IrGuidanceSource.evaluateEntitySeeker(context, source, RVP_EnumGuidanceType.SARH);
    }
}
