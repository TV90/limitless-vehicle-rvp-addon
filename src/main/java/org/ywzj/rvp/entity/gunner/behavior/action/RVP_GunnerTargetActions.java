package org.ywzj.rvp.entity.gunner.behavior.action;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerEngagementNet;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorContext;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 管理器提交 TARGET 胜者时使用的目标同步动作。 */
public final class RVP_GunnerTargetActions {

    /** 提交权威目标，并在真正切换目标时登记组网排斥窗口。 */
    public RVP_GunnerActionResult commit(RVP_GunnerBehaviorContext context, @Nullable Entity target) {
        if (target != null) {
            markEngagementNetOnTrack(context.gunner(), context.vehicle(), target, context.profile());
        }
        context.gunner().setTrackedTarget(target);
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** 新目标开始跟踪时登记软窗口；同一目标的周期性重扫不续窗。 */
    private static void markEngagementNetOnTrack(GunnerEntity gunner, AbstractVehicle vehicle,
                                                 Entity target, GunnerProfile profile) {
        if (target.getId() == gunner.getTrackedTargetId()) {
            return;
        }
        // 调用组网窗口策略，按当前交战距离生成与开火侧一致的排斥时长。
        long windowTick = RVP_GunnerEngagementNet.resolveWindowTick(
                vehicle, target, profile.getEngagementNetCooldownTick());
        if (windowTick > 0L) {
            // 调用组网侧表登记新跟踪目标，防止同阵营 Gunner 重复占用目标。
            RVP_GunnerEngagementNet.markTracked(vehicle.level(), gunner.getProfileFaction(), target, windowTick);
        }
    }
}
