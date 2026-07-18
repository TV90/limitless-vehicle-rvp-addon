package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.rvp.guidance.RVP_WireGuidanceSteering;
import org.ywzj.vehicle.util.VectorUtil;

/** HITL mouse command guidance with direct motion application. */
public final class RVP_RuntimeHitlClosTvGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.HITL_CLOS_TV;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (!(context.projectile() instanceof RVP_MissileEntity missile)
                || !missile.rvp$isHitlActive()
                || missile.rvp$getHitlControlMode() != RVP_EnumHitlControlMode.MOUSE) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_CLOS_TV);
        }
        Vec2 command = RVP_WireGuidanceSteering.resolveCommand(context.projectile()).orElse(null);
        if (command == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_CLOS_TV);
        }
        Vec3 direction = VectorUtil.rotToVec(command.x, command.y);
        if (direction.lengthSqr() <= 1.0E-6) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_CLOS_TV);
        }
        double range = RVP_GuidanceRuntimeGeometry.resolveScanRadius(
                context.active().targetDistanceRange());
        Vec3 point = context.projectile().position().add(direction.normalize().scale(range));
        return RVP_GuidanceIntent.point(point, true, 1.0, RVP_EnumGuidanceType.HITL_CLOS_TV);
    }
}
