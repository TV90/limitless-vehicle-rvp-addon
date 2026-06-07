package org.ywzj.rvp.guidance.source;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceEffectiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSeekerUtil;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

public final class RVP_IrGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.IR;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        return evaluateEntitySeeker(context, source, RVP_EnumGuidanceType.IR);
    }

    static RVP_GuidanceIntent evaluateEntitySeeker(
            RVP_GuidanceContext context,
            RVP_GuidanceData.Source source,
            RVP_EnumGuidanceType type
    ) {
        RVP_BaseBullet projectile = context.projectile();
        RVP_GuidanceEffectiveConfig config = context.effective();
        boolean vehicleOnly = source.getParams().vehicleOnly(type == RVP_EnumGuidanceType.IR);

        Entity target = projectile.getTargetEntity();
        if (target != null && target.isAlive()) {
            if (!RVP_GuidanceSeekerUtil.isValidEntityTarget(projectile, config, type, target)) {
                projectile.clearTarget();
                return RVP_GuidanceIntent.failed(type);
            }
            target = projectile.getTargetEntity();
            if (target == null || !target.isAlive()) {
                return RVP_GuidanceIntent.failed(type);
            }
            if (type == RVP_EnumGuidanceType.IR) {
                projectile.setTargetPos(target.position().add(0, target.getBbHeight() * 0.5, 0));
            } else {
                projectile.rememberGuidancePos(target.getBoundingBox().getCenter());
            }
            return RVP_GuidanceIntent.entity(target, source.isTakeOverMotion(), source.getWeight(), type);
        }

        if (projectile.tickCount % config.seeker().getScanIntervalTick() != 0) {
            return RVP_GuidanceIntent.failed(type);
        }
        Entity scanned = RVP_GuidanceSeekerUtil.scanSeekerTarget(projectile, config, type, vehicleOnly);
        if (scanned == null) {
            return RVP_GuidanceIntent.failed(type);
        }
        projectile.setTargetEntity(scanned);
        return RVP_GuidanceIntent.entity(scanned, source.isTakeOverMotion(), source.getWeight(), type);
    }
}
