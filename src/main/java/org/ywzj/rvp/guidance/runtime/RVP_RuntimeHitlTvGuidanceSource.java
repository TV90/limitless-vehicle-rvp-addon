package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosDesignation;

/** TV designation guidance with the existing HITL target re-designation flow. */
public final class RVP_RuntimeHitlTvGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.HITL_TV;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (!(context.projectile() instanceof RVP_MissileEntity missile)
                || !missile.rvp$isHitlActive()
                || missile.rvp$getHitlControlMode() != RVP_EnumHitlControlMode.DESIGNATE) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_TV);
        }
        Entity target = context.projectile().getTargetEntity();
        if (target != null && target.isAlive()) {
            Entity valid = RVP_RuntimeSeekerSupport.validateEntity(
                    context.projectile(), target, RVP_EnumGuidanceType.HITL_TV, context.active());
            if (valid != null) {
                return RVP_GuidanceIntent.entity(valid, false, 1.0, RVP_EnumGuidanceType.HITL_TV);
            }
        }
        Vec3 point = RVP_SaclosDesignation.resolveDesignationPoint(context.projectile());
        if (point == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_TV);
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(
                context.projectile(), point, RVP_EnumGuidanceType.HITL_TV, context.active());
        if (result.isDenied()) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_TV);
        }
        context.projectile().setTargetPos(point);
        return RVP_GuidanceIntent.point(point, false, 1.0, RVP_EnumGuidanceType.HITL_TV);
    }
}
