package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

public final class RVP_RuntimeGpsGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.GPS;
    }

    private static final double INERTIAL_RANGE = 5.0;

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        Vec3 target = context.projectile().getTargetPos();
        if (target == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.GPS);
        }
        if (context.projectile().position().distanceToSqr(target) <= INERTIAL_RANGE * INERTIAL_RANGE) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.GPS);
        }
        return RVP_GuidanceIntent.point(target, false, 1.0, RVP_EnumGuidanceType.GPS);
    }
}
