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
        // PRESET 弹在近距离发射时不能立即切到终端 ARH：冷发射仍是纯竖直速度，
        // 终端 ARH 的角度门会拒绝从竖直轴直接转向 GPS 点，造成“直飞冲天”。
        // 调用本项目制导阶段状态机时，先保留主段直到冷发射/点火窗口结束。
        if (!shouldDeferPresetTerminalTransition(
                guidance,
                projectile.getFlightTickCount(),
                projectile.getColdLaunchTimeTick(),
                data.getResolvedIgnitionDelayTick())) {
            projectile.getGuidancePhaseState().update(
                    guidance.getTerminalGuidance(),
                    RVP_GuidanceTransitionContext.from(projectile)
            );
        }
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

    /**
     * 判断 PRESET 弹是否仍处于必须保留主段制导的发射窗口。
     *
     * <p>该门只影响已经配置 PRESET 且带终端段的弹体；普通 GPS/ARH 弹仍完全使用原有
     * 终端切换条件。窗口取冷发射时长和发动机点火延迟的较大值，确保主段至少有机会
     * 写入一帧非竖直速度后，再允许近距离目标触发终端段。</p>
     */
    static boolean shouldDeferPresetTerminalTransition(
            RVP_GuidanceData guidance,
            int flightTick,
            int coldLaunchTick,
            int ignitionDelayTick
    ) {
        if (guidance == null
                || guidance.getTerminalGuidance() == null
                || !RVP_PresetBallisticProfile.of(guidance).active()) {
            return false;
        }
        int launchWindowEnd = Math.max(
                Math.max(coldLaunchTick, 0), Math.max(ignitionDelayTick, 0));
        return Math.max(flightTick, 0) < launchWindowEnd;
    }
}
