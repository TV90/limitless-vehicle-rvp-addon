package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceTargetUtil;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

public final class RVP_RuntimeSarhGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.SARH;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        // 主动ECM干扰：阻断半主动雷达照射（SARH 需照射源，干扰期视为无照射）
        if (context.projectile().ecmActiveJamRemainTick > 0) {
            context.projectile().clearTarget();
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SARH);
        }
        Entity illuminated = RVP_GuidanceTargetUtil.getStrictRadarIlluminatedTarget(context.projectile());
        if (illuminated == null || !illuminated.isAlive()) {
            context.projectile().clearTarget();
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SARH);
        }
        context.projectile().setTargetEntity(illuminated);
        Entity target = RVP_RuntimeSeekerSupport.validateEntity(
                context.projectile(), illuminated, RVP_EnumGuidanceType.SARH, context.active());
        if (target == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SARH);
        }
        return RVP_GuidanceIntent.entity(target, false, 1.0, RVP_EnumGuidanceType.SARH);
    }
}
