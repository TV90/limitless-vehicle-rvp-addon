package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidancePhase;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

public final class RVP_RuntimeIrGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.IR;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        RVP_BaseBullet projectile = context.projectile();
        Entity target = projectile.getTargetEntity();
        if (target == null
                && context.active().phase() == RVP_GuidancePhase.TERMINAL
                && !projectile.hasTerminalIrTargetAcquired()
                && !projectile.isSeekerShutOff()
                && !projectile.hasSmokeBreakLock()) {
            int interval = context.active().scanIntervalTick() != null
                    ? context.active().scanIntervalTick()
                    : 2;
            if (projectile.getFlightTickCount() % interval == 0) {
                target = RVP_RuntimeSeekerSupport.scanInfraredTarget(projectile, context.active());
                if (target != null) {
                    projectile.setTargetEntity(target);
                    projectile.markTerminalIrTargetAcquired();
                }
            }
        }
        if (target != null && context.active().phase() == RVP_GuidancePhase.TERMINAL) {
            projectile.markTerminalIrTargetAcquired();
        }
        if (target != null && target.isAlive()) {
            Entity valid = RVP_RuntimeSeekerSupport.validateEntity(
                    projectile, target, RVP_EnumGuidanceType.IR, context.active());
            if (valid != null) {
                projectile.resetIrSeekerGrace();
                return RVP_GuidanceIntent.entity(valid, false, 1.0, RVP_EnumGuidanceType.IR);
            }
            projectile.beginIrSeekerLossGrace(context.active().angleGateLockOutTick());
            if (projectile.hasIrSeekerGrace()) {
                return RVP_GuidanceIntent.entity(target, false, 1.0, RVP_EnumGuidanceType.IR);
            }
            projectile.clearTarget();
        }
        return inertialOrFailed(context);
    }

    private RVP_GuidanceIntent inertialOrFailed(RVP_GuidanceRuntimeContext context) {
        RVP_BaseBullet projectile = context.projectile();
        // 烟雾脱锁：朝脱锁瞬间算定的固定落点（烟雾 AABB 内、远离最后目标）惯性飞行
        if (projectile.hasSmokeBreakLock() && projectile.getSmokeInertialPoint() != null) {
            return RVP_GuidanceIntent.point(
                    projectile.getSmokeInertialPoint(), false, 1.0, RVP_EnumGuidanceType.IR);
        }
        if (context.active().enableInertialGuidance() && projectile.getLastGuidancePos() != null) {
            return RVP_GuidanceIntent.point(
                    projectile.getLastGuidancePos(), false, 1.0, RVP_EnumGuidanceType.IR);
        }
        return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.IR);
    }
}
