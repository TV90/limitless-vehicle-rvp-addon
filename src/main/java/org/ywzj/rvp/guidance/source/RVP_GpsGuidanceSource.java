package org.ywzj.rvp.guidance.source;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

public final class RVP_GpsGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.GPS;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        RVP_BaseBullet projectile = context.projectile();
        if (!source.getParams().useTargetPos(true)) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.GPS);
        }
        Vec3 target = projectile.getTargetPos();
        if (target == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.GPS);
        }
        float cancelDistance = context.data().getProjectileData().getGpsGuidanceCancelDistance();
        if (cancelDistance > 0f && projectile.position().distanceTo(target) <= cancelDistance) {
            projectile.clearTarget();
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.GPS);
        }
        return RVP_GuidanceIntent.point(target, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.GPS);
    }
}
