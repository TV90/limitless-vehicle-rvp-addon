package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosDesignation;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;

/** Shared laser-spot guidance for LH and SALH. */
public final class RVP_RuntimeLaserGuidanceSource implements RVP_RuntimeGuidanceSource {

    private final RVP_EnumGuidanceType type;

    public RVP_RuntimeLaserGuidanceSource(RVP_EnumGuidanceType type) {
        if (type != RVP_EnumGuidanceType.LH && type != RVP_EnumGuidanceType.SALH) {
            throw new IllegalArgumentException("Laser runtime source supports only LH or SALH");
        }
        this.type = type;
    }

    @Override
    public RVP_EnumGuidanceType type() {
        return type;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (!RVP_SaclosOperatorSession.isLaserEnabled(context.projectile())) {
            return RVP_GuidanceIntent.failed(type);
        }
        Vec3 point = RVP_SaclosDesignation.resolveDesignationPoint(context.projectile());
        if (point == null) {
            return RVP_GuidanceIntent.failed(type);
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(
                context.projectile(), point, type, context.active());
        if (result.isDenied()) {
            return RVP_GuidanceIntent.failed(type);
        }
        context.projectile().setTargetPos(point);
        return RVP_GuidanceIntent.point(point, false, 1.0, type);
    }
}
