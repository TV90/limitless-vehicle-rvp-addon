package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.RVP_CommandGuidanceAim;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

/** True SACLOS: follows the operator's current line of sight. */
public final class RVP_RuntimeSaclosGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.SACLOS;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        Vec3 direction = RVP_CommandGuidanceAim.operatorAimDirection(
                context.projectile().getShooterWeaponUnit());
        if (direction == null || direction.lengthSqr() <= 1.0E-6) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
        }
        double range = RVP_GuidanceRuntimeGeometry.resolveScanRadius(
                context.active().targetDistanceRange());
        Vec3 point = context.projectile().position().add(direction.normalize().scale(range));
        context.projectile().setTargetPos(point);
        return RVP_GuidanceIntent.point(point, true, 1.0, RVP_EnumGuidanceType.SACLOS);
    }
}
