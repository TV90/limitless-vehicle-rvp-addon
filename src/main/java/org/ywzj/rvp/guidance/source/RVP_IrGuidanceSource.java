package org.ywzj.rvp.guidance.source;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceEffectiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSeekerUtil;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

public final class RVP_IrGuidanceSource implements RVP_GuidanceSource {

    private static final int LAUNCH_LOCK_BRIDGE_TICKS = 5;
    private static final int TRACK_GRACE_TICKS = 20;

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
        boolean allowReacquire = source.getParams().reacquire(false);
        boolean hadLaunchSnapshot = projectile.hasLaunchTargetSnapshot();

        Entity target = projectile.getTargetEntity();

        // Spawn grace: allow a brand-new round without its own snapshot to bridge from the launcher lock once.
        if ((target == null || !target.isAlive()) && shouldBridgeLaunchLock(projectile, hadLaunchSnapshot)) {
            Entity illuminated = RVP_GuidanceSeekerUtil.getIlluminatedTarget(projectile);
            if (illuminated != null && illuminated.isAlive()) {
                projectile.setTargetEntity(illuminated);
                target = illuminated;
            }
        }

        if (target != null && target.isAlive()) {
            if (RVP_GuidanceSeekerUtil.isValidEntityTarget(projectile, config, type, target)) {
                projectile.resetIrSeekerGrace();
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
            if (type == RVP_EnumGuidanceType.IR) {
                if (RVP_GuidanceSeekerUtil.isValidEntityTrack(projectile, config, type, target)) {
                    projectile.resetIrSeekerGrace();
                    projectile.setTargetPos(target.position().add(0, target.getBbHeight() * 0.5, 0));
                    return RVP_GuidanceIntent.entity(target, source.isTakeOverMotion(), source.getWeight(), type);
                }
                if (canHoldIrGrace(projectile, config, target)) {
                    if (!projectile.hasIrSeekerGrace()) {
                        projectile.beginIrSeekerGrace(TRACK_GRACE_TICKS);
                    }
                    if (projectile.hasIrSeekerGrace()) {
                        projectile.setTargetPos(target.position().add(0, target.getBbHeight() * 0.5, 0));
                        return RVP_GuidanceIntent.entity(target, source.isTakeOverMotion(), source.getWeight(), type);
                    }
                }
            }
            projectile.resetIrSeekerGrace();
            projectile.clearTarget();
            if (hadLaunchSnapshot && !allowReacquire) {
                return RVP_GuidanceIntent.failed(type);
            }
        }

        // Snapshot-fired rounds do not drift to the launcher's new lock unless the source explicitly allows reacquire.
        if (hadLaunchSnapshot && !allowReacquire) {
            return RVP_GuidanceIntent.failed(type);
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

    private static boolean shouldBridgeLaunchLock(RVP_BaseBullet projectile, boolean hadLaunchSnapshot) {
        return !hadLaunchSnapshot && projectile.tickCount <= LAUNCH_LOCK_BRIDGE_TICKS;
    }

    private static boolean canHoldIrGrace(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            Entity target
    ) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        double range = config.seeker().resolvedRange();
        if (projectile.position().distanceToSqr(target.getBoundingBox().getCenter()) > range * range) {
            return false;
        }
        if (!org.ywzj.rvp.guidance.RVP_GuidanceMath.isTargetPassAltFilter(target, config.seeker().getLockMinHeight())) {
            return false;
        }
        var result = org.ywzj.rvp.countermeasure.RVP_CountermeasureState.query(
                projectile, target, RVP_EnumGuidanceType.IR, config.seeker());
        if (result.intercepted()) {
            projectile.discard();
            return false;
        }
        return !result.isDenied();
    }
}
