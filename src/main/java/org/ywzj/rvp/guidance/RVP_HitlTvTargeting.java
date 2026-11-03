package org.ywzj.rvp.guidance;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_Config;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status;

/** TV 输入与飞行跟踪共用的服务端接纳门；检查通过前不得刷新目标或惯性记忆。 */
public final class RVP_HitlTvTargeting {
    private RVP_HitlTvTargeting() {}

    public static boolean validate(RVP_MissileEntity missile, @Nullable Entity target, Vec3 point,
            RVP_GuidanceActiveConfig config) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) return false;
        // 调用已有光学/APS 查询，使用玩家选中的 OBB 点而非另一处实体中心。
        var result = RVP_CountermeasureState.queryPoint(missile, point, RVP_EnumGuidanceType.HITL_TV, config);
        if (result.intercepted()) {
            missile.rvp$freezeHitlTarget(null, Status.NONE);
            missile.discard();
            return false;
        }
        if (result.isDenied()) {
            Entity smoke = RVP_CountermeasureState.findSightObstruction(missile, missile.position(), point);
            // 烟雾干扰只随机取点一次，不再读取原目标的坐标/速度；普通遮挡保留最后有效点。
            Vec3 frozen = smoke == null ? missile.getTargetPos() : smokePoint(smoke);
            missile.rvp$freezeHitlTarget(frozen, smoke == null ? Status.OCCLUDED : Status.SMOKE);
            missile.markJamGracePeriod();
            return false;
        }
        if (target != null && !RVP_GuidanceRuntimeGeometry.passesTrackEnvelope(missile, point, config)) {
            missile.rvp$freezeHitlTarget(missile.getTargetPos(), Status.OCCLUDED);
            return false;
        }
        // 沿用 3D 格/tick × 72 = km/h、严格大于阈值及 0 禁用的现有规则。
        float limit = RVP_Config.getHitlTvBreakLockTargetSpeedKph();
        if (target != null && limit > 0 && target.getDeltaMovement().length() * 72.0 > limit) {
            missile.rvp$markHitlSpeedBreak(point);
            if (missile.getOwner() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable("ui.hitl_target_speed_break"), true);
            }
            return false;
        }
        return true;
    }

    /** 烟中心五格以内的一次性偏移点，且始终在实际烟体内；不借用 IR 的永久脱锁标志。 */
    private static Vec3 smokePoint(Entity smoke) {
        var box = smoke.getBoundingBox();
        double radius = Math.min(5.0, Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) * 0.45);
        var random = net.minecraft.util.RandomSource.create();
        double yaw = random.nextDouble() * Math.PI * 2;
        double elevation = random.nextDouble() * 2 - 1;
        double horizontal = Math.sqrt(1 - elevation * elevation);
        double distance = radius * Math.cbrt(random.nextDouble());
        return box.getCenter().add(Math.cos(yaw) * horizontal * distance, elevation * distance,
                Math.sin(yaw) * horizontal * distance);
    }
}
