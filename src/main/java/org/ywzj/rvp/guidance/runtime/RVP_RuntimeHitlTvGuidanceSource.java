package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosDesignation;

/** TV designation guidance with the existing HITL target re-designation flow. */
public final class RVP_RuntimeHitlTvGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.HITL_TV;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (!(context.projectile() instanceof RVP_MissileEntity missile)
                || missile.rvp$getHitlControlMode() != RVP_EnumHitlControlMode.DESIGNATE) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_TV);
        }
        // HITL 失活（玩家右键退出视角/链路切断）后：导弹继续跟踪最后锁定的目标实体或目标点，
        // 而非退回无制导直飞。若存在存活目标实体，优先实体跟踪；否则沿用最后的指定目标点。
        boolean hitlActive = missile.rvp$isHitlActive();
        Entity target = context.projectile().getTargetEntity();
        if (target != null && target.isAlive()) {
            // 反制链：validateEntity 内含 isTargetInsideSmoke / 视线遮挡检测，目标进入烟雾
            // AABB 碰撞箱会判定锁定阻断（denied），该路径已进入干扰保持期并脱锁，符合烟雾弹预期。
            Entity valid = RVP_RuntimeSeekerSupport.validateEntity(
                    context.projectile(), target, RVP_EnumGuidanceType.HITL_TV, context.active());
            if (valid != null) {
                return RVP_GuidanceIntent.entity(valid, false, 1.0, RVP_EnumGuidanceType.HITL_TV);
            }
        }
        // 无存活实体目标时：HITL 仍激活则继续解析实时指定点（吊舱瞄准/操作手指定），
        // HITL 已退出则锁定最后的指定目标点，避免退出后随吊舱实时瞄准漂移（鼠标操控 bug 根因
        // 由 RVP_SaclosDesignation.tickUpdateLiveTarget 的 isSaclosTvGuided 门控一并堵住）。
        Vec3 point = hitlActive
                ? RVP_SaclosDesignation.resolveDesignationPoint(context.projectile())
                : context.projectile().getTargetPos();
        if (point == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_TV);
        }
        // 点制导反制链：queryPoint 对 HITL_TV 走 isPointInsideSmoke / 视线遮挡检测，与实体路径
        // 一致，目标进入烟雾 AABB 时判定 denied（脱锁），维持烟雾弹对退出后追踪导弹的威慑。
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(
                context.projectile(), point, RVP_EnumGuidanceType.HITL_TV, context.active());
        if (result.isDenied()) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.HITL_TV);
        }
        context.projectile().setTargetPos(point);
        return RVP_GuidanceIntent.point(point, false, 1.0, RVP_EnumGuidanceType.HITL_TV);
    }
}
