package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

public final class RVP_RuntimeArhGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.ARH;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (!(context.projectile() instanceof RVP_MissileEntity missile)) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
        }
        Entity target = missile.getTargetEntity();
        Entity designated = missile.rvp$getArhDesignatedTargetEntity();
        boolean freeAcquire = missile.rvp$canArhFreeAcquire();

        if (!freeAcquire && designated != null && designated.isAlive()) {
            target = designated;
            missile.setTargetEntity(designated);
        }

        if (!missile.isActiveRadarOn()) {
            if (target != null && target.isAlive()) {
                Vec3 point = target.getBoundingBox().getCenter();
                missile.setTargetPos(point);
                return RVP_GuidanceIntent.point(point, false, 1.0, RVP_EnumGuidanceType.ARH);
            }
            Vec3 memory = missile.getTargetPos() != null ? missile.getTargetPos() : missile.getLastGuidancePos();
            return memory == null
                    ? RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH)
                    : RVP_GuidanceIntent.point(memory, false, 1.0, RVP_EnumGuidanceType.ARH);
        }

        if (target != null && target.isAlive()) {
            target = RVP_RuntimeSeekerSupport.validateEntity(
                    missile, target, RVP_EnumGuidanceType.ARH, context.active());
            if (target != null) {
                return RVP_GuidanceIntent.entity(target, false, 1.0, RVP_EnumGuidanceType.ARH);
            }
            if (!freeAcquire) {
                return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
            }
            missile.clearTarget();
        }

        int interval = context.active().scanIntervalTick() != null ? context.active().scanIntervalTick() : 2;
        if (!freeAcquire || missile.tickCount % interval != 0) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
        }
        Entity scanned = RVP_RuntimeSeekerSupport.scanRadarTarget(missile, context.active());
        if (scanned == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.ARH);
        }
        missile.setTargetEntity(scanned);
        return RVP_GuidanceIntent.entity(scanned, false, 1.0, RVP_EnumGuidanceType.ARH);
    }
}
