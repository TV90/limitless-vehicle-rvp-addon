package org.ywzj.rvp.guidance.source;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

public final class RVP_IogGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.IOG;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        RVP_BaseBullet projectile = context.projectile();
        Vec3 aim = null;
        if (source.getParams().useLastGuidance(true)) {
            aim = projectile.getLastGuidancePos();
        }
        if (aim == null) {
            aim = projectile.getTargetPos();
        }
        if (aim == null && projectile.getTargetEntity() != null && projectile.getTargetEntity().isAlive()) {
            aim = projectile.getTargetEntity().getBoundingBox().getCenter();
        }
        if (aim == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.IOG);
        }
        return RVP_GuidanceIntent.point(aim, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.IOG);
    }
}
