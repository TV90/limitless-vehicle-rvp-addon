package org.ywzj.rvp.guidance.source;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceEffectiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.guidance.RVP_GuidanceSeekerUtil;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

public final class RVP_ArhGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.ARH;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        if (!(context.projectile() instanceof RVP_MissileEntity)) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
        }
        RVP_BaseBullet projectile = context.projectile();
        RVP_GuidanceEffectiveConfig config = context.effective();
        Entity target = projectile.getTargetEntity();
        if (target != null && target.isAlive()) {
            if (!isValidRadarTarget(projectile, config, target)) {
                projectile.clearTarget();
                return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
            }
            target = projectile.getTargetEntity();
            if (target == null || !target.isAlive()) {
                return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
            }
            projectile.setTargetPos(target.position().add(0, target.getBbHeight() * 0.5, 0));
            return RVP_GuidanceIntent.entity(target, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.ARH);
        }

        if (projectile.tickCount % config.seeker().getScanIntervalTick() != 0) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
        }
        Entity scanned = RVP_GuidanceSeekerUtil.scanRadarTarget(projectile, config);
        if (scanned == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
        }
        projectile.setTargetEntity(scanned);
        return RVP_GuidanceIntent.entity(scanned, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.ARH);
    }

    private static boolean isValidRadarTarget(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            Entity target
    ) {
        if (!RVP_GuidanceMath.isWithinSeekerCone(projectile, target, config)) {
            return false;
        }
        if (!RVP_GuidanceMath.isTargetPassAltFilter(target, config.seeker().getLockMinHeight())) {
            return false;
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(
                projectile, target, RVP_EnumGuidanceType.ARH, config.seeker());
        if (result.intercepted()) {
            projectile.discard();
            return false;
        }
        if (result.decoyed()) {
            RVP_CountermeasureState.findDecoyTarget(target, 16.0).ifPresent(projectile::setTargetEntity);
            return true;
        }
        return !result.isDenied();
    }
}
