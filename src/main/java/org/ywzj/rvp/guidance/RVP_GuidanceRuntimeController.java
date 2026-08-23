package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.dircm.RVP_JamDeflectionHelper;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** Executes exactly one guidance type from the new MAIN/TERMINAL schema. */
public final class RVP_GuidanceRuntimeController {

    private RVP_GuidanceRuntimeController() {}

    public static boolean tick(RVP_BaseBullet projectile) {
        // DIRCM 被干扰：丢制导（不执行任何制导源，等效 NONE 意图直飞）+ 每 tick 强制偏转。
        // 普通 IR/ARH 弹为永久干扰；HITL 弹由 RVP_DircmRuntimeManager 递减 dircmJamRemainTick
        // 归零后恢复制导。双端安全：无任何客户端类型引用。
        if (projectile.dircmJammed) {
            if (!projectile.level().isClientSide()) {
                RVP_JamDeflectionHelper.applyDeflection(projectile);
            }
            return false;
        }
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null) {
            return false;
        }
        RVP_GuidanceData guidance = data.getGuidanceData();
        projectile.getGuidancePhaseState().update(
                guidance.getTerminalGuidance(),
                RVP_GuidanceTransitionContext.from(projectile)
        );
        RVP_GuidanceActiveConfig active = RVP_GuidanceModelResolver.resolveActive(
                guidance,
                projectile.getGuidancePhaseState().phase()
        );
        projectile.setActiveStageName(active.phase().name());
        projectile.setActiveSourceType(active.guidanceType());

        RVP_Range<Integer> tickRange = active.tickRange();
        if (tickRange != null && !tickRange.contains(projectile.getFlightTickCount())) {
            return false;
        }

        RVP_RuntimeGuidanceSource source = RVP_RuntimeGuidanceSourceRegistry.get(active.guidanceType());
        if (source == null) {
            return false;
        }

        RVP_GuidanceRuntimeContext context = new RVP_GuidanceRuntimeContext(projectile, data, active);
        RVP_GuidanceIntent intent = source.evaluate(context);
        if (!intent.success() && active.enableInertialGuidance()) {
            Vec3 memory = projectile.getLastGuidancePos();
            if (memory != null) {
                intent = RVP_GuidanceIntent.point(memory, false, 1.0, active.guidanceType());
            }
        }
        if (!intent.success()) {
            return false;
        }
        return RVP_GuidanceRuntimeMath.applyIntent(context, intent);
    }
}
