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
        // 调用统一阶段入口；导弹在管理导引头前也调用同一入口，重复调用不会越过保护门。
        updatePhase(projectile);
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

    /** 统一实体与通用制导的阶段更新，避免实体先切段绕过冷发射保护。 */
    public static void updatePhase(RVP_BaseBullet projectile) {
        // 调用配置出口，无配置或被强制干扰时不推进阶段。
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || projectile.dircmJammed) return;
        RVP_GuidanceData guidance = data.getGuidanceData();
        if (guidance.getTerminalGuidance() == null || projectile.getGuidancePhaseState().isTerminal()) return;
        if (guidance.getGuidanceType() != RVP_EnumGuidanceType.GPS
                || !RVP_PresetBallisticProfile.of(guidance).active()) {
            // 非预设弹只调用原状态机，不增加世界高度查询与姿态门。
            projectile.getGuidancePhaseState().update(guidance.getTerminalGuidance(),
                    RVP_GuidanceTransitionContext.from(projectile));
            return;
        }
        // 调用共同目标解析器，切段距离和可接管方向必须对着同一个目标。
        Vec3 target = RVP_GuidanceTransitionContext.resolveTarget(projectile);
        RVP_GuidanceActiveConfig terminal = RVP_GuidanceModelResolver.resolveActive(
                guidance, RVP_GuidancePhase.TERMINAL);
        // 预设弹先完成上升转弯，进入下降侧后才交给末段雷达，避免近距离发射刚转头就切走。
        boolean canSteer = target != null && projectile.getDeltaMovement().y <= 0.0
                && RVP_GuidanceRuntimeGeometry.passesTrackLimits(projectile, target, terminal);
        // 调用可单测阶段入口；计时、初始化、姿态就绪必须一起通过。
        updatePhase(projectile.getGuidancePhaseState(), guidance,
                RVP_GuidanceTransitionContext.from(projectile), projectile.getColdLaunchTimeTick(),
                data.getResolvedIgnitionDelayTick(), projectile.hasPresetProfileInitialized(), canSteer);
    }

    /** 纯阶段编排供边界回归使用；非 PRESET 保留原有切换条件。 */
    static boolean updatePhase(RVP_GuidancePhaseState state, RVP_GuidanceData guidance,
            RVP_GuidanceTransitionContext context, int coldLaunchTick, int ignitionTick,
            boolean presetInitialized, boolean terminalCanSteer) {
        // 调用 PRESET 参数与冷发射门；未初始化或姿态不可接管时继续主段转弯。
        boolean preset = guidance.getGuidanceType() == RVP_EnumGuidanceType.GPS
                && RVP_PresetBallisticProfile.of(guidance).active();
        if (preset && (shouldDeferPresetTerminalTransition(guidance, context.tick(),
                coldLaunchTick, ignitionTick) || !presetInitialized || !terminalCanSteer)) return false;
        // 调用原状态机保留单向切换语义，不倒退已生效的阶段。
        return state.update(guidance.getTerminalGuidance(), context);
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
