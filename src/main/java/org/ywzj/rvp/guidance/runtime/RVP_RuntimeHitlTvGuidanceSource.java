package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.*;
import org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status;

/** TV 实体锁、指定点与反制后的固定点；退出镜头后仍执行相同检查。 */
public final class RVP_RuntimeHitlTvGuidanceSource implements RVP_RuntimeGuidanceSource {
    @Override
    public RVP_EnumGuidanceType type() { return RVP_EnumGuidanceType.HITL_TV; }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (!(context.projectile() instanceof RVP_MissileEntity missile)
                || missile.rvp$getHitlControlMode() != RVP_EnumHitlControlMode.DESIGNATE) {
            return RVP_GuidanceIntent.failed(type());
        }
        // 固定烟内点也要经过 APS 拦截门，但不能每 tick 重新生成偏移点。
        if (RVP_CountermeasureState.queryPoint(missile, null, type(), context.active()).intercepted()) {
            missile.rvp$freezeHitlTarget(null, Status.NONE);
            missile.discard();
            return RVP_GuidanceIntent.failed(type());
        }
        Entity target = missile.getTargetEntity();
        if (target != null && target.isAlive()) {
            Vec3 point = missile.rvp$resolveHitlAimPoint(target);
            // 调用与 C2S 指定一致的门；只有通过后才更新惯性记忆，避免“失败但仍跟踪”。
            if (RVP_HitlTvTargeting.validate(missile, target, point, context.active())) {
                missile.setTargetPos(point);
                return new RVP_GuidanceIntent(true, point, target, false, 1.0, type());
            }
        } else if (target != null) {
            missile.rvp$freezeHitlTarget(missile.getTargetPos(), Status.OCCLUDED);
        } else if (missile.getTargetPos() != null && missile.rvp$getHitlPointStatus() != Status.SMOKE
                && missile.rvp$getHitlPointStatus() != Status.OCCLUDED) {
            RVP_HitlTvTargeting.validate(missile, null, missile.getTargetPos(), context.active());
        }
        Vec3 point = missile.getTargetPos();
        // 其它遮挡遵从原 enable_inertial_guidance；烟雾按既有设计偏向一次性烟内点。
        if (point == null || missile.rvp$getHitlPointStatus() == Status.OCCLUDED) {
            return RVP_GuidanceIntent.failed(type());
        }
        return RVP_GuidanceIntent.point(point, false, 1.0, type());
    }
}
