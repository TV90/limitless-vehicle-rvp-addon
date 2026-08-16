package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;

final class RVP_RuntimeActiveSeekerGuidance {

    private RVP_RuntimeActiveSeekerGuidance() {}

    static RVP_GuidanceIntent evaluate(
            RVP_GuidanceRuntimeContext context,
            RVP_EnumGuidanceType type
    ) {
        if (!(context.projectile() instanceof RVP_MissileEntity missile)) {
            return RVP_GuidanceIntent.failed(type);
        }

        Entity designated = missile.rvp$getActiveSeekerDesignatedTargetEntity();
        boolean freeAcquire = missile.rvp$canActiveSeekerFreeAcquire();
        Entity target = missile.getTargetEntity();

        if (!missile.isAutonomousSeekerOn()) {
            if (!freeAcquire && designated != null) {
                missile.setTargetEntity(designated);
                Vec3 point = designated.getBoundingBox().getCenter();
                missile.setTargetPos(point);
                return RVP_GuidanceIntent.entity(designated, false, 1.0, type);
            }
            Vec3 memory = missile.getTargetPos() != null
                    ? missile.getTargetPos()
                    : missile.getLastGuidancePos();
            return memory == null
                    ? RVP_GuidanceIntent.failed(type)
                    : RVP_GuidanceIntent.point(memory, false, 1.0, type);
        }

        if (!missile.hasAutonomousSeekerCatch() && designated != null) {
            Entity acquired = RVP_RuntimeSeekerSupport.acquireEntity(
                    missile, designated, type, context.active());
            if (acquired != null) {
                missile.setTargetEntity(acquired);
                missile.markAutonomousSeekerCatch();
                return RVP_GuidanceIntent.entity(acquired, false, 1.0, type);
            }
            if (!freeAcquire) {
                return RVP_GuidanceIntent.failed(type);
            }
        }

        if (target != null && target.isAlive() && missile.hasAutonomousSeekerCatch()) {
            Entity tracked = RVP_RuntimeSeekerSupport.validateEntity(
                    missile, target, type, context.active());
            if (tracked != null) {
                return RVP_GuidanceIntent.entity(tracked, false, 1.0, type);
            }
            missile.setTargetEntity(null);
        }

        int interval = context.active().scanIntervalTick() != null
                ? context.active().scanIntervalTick()
                : 2;
        // 导引头关闭期（被干扰失锁后 seekerShutOffTime 内）不主动扫描复锁；
        // 干扰保持期（脱锁判定成立但未重锁）内同样不重扫——避免刚被干扰脱锁就立即重锁机体直击玩家
        if (missile.isSeekerShutOff()
                || missile.isJamGracePeriodActive()
                || !freeAcquire || missile.getFlightTickCount() % interval != 0) {
            return RVP_GuidanceIntent.failed(type);
        }

        Entity scanned = (type == RVP_EnumGuidanceType.ARH || type == RVP_EnumGuidanceType.AIR)
                ? RVP_RuntimeSeekerSupport.scanRadarTarget(missile, context.active())
                : RVP_RuntimeSeekerSupport.scanInfraredTarget(missile, context.active());
        if (scanned == null) {
            return RVP_GuidanceIntent.failed(type);
        }
        missile.setTargetEntity(scanned);
        missile.markAutonomousSeekerCatch();
        return RVP_GuidanceIntent.entity(scanned, false, 1.0, type);
    }
}
