package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.firecontrol.RVP_BallisticLeadFireControlPolicy;
import org.ywzj.rvp.client.firecontrol.RVP_FireControlModeMask;
import org.ywzj.rvp.debug.RVP_LeadFcDebug;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.WeakHashMap;

public final class RVP_FireControlStabilizerState {
    public enum Mode {
        STABLE,
        SEMI_AUTO,
        OFF
    }

    /** 各根火控武器站当前选择的三态模式；弱键避免载具卸载后残留客户端状态。 */
    private static final java.util.Map<WeaponUnit, Mode> MODES = new WeakHashMap<>();

    private RVP_FireControlStabilizerState() {}

    public static Mode getMode(@Nullable WeaponUnit unit) {
        if (unit == null || !isEligible(unit)) {
            return Mode.SEMI_AUTO;
        }
        Mode selected = MODES.get(unit);
        if (selected != null) {
            return selected;
        }
        // 未手动切换过的武器站：默认半自动；子集不含半自动时取配置数组首个合法项（对齐 hitl_video_modes 语义）。
        return resolveInitialMode(resolveAllowedMask(unit));
    }

    /** 火控稳定器键按下时切换稳定模式。由按键消费方保证是 FIRE_CONTROL_STABILIZER 键。 */
    public static boolean tryHandleToggleKey(@Nullable WeaponUnit unit) {
        if (unit == null || !isEligible(unit)) {
            // [RVP] 诊断（/rvpdebug flags lead_fc on）：T 键切换被资格判定拒绝时落盘原因，
            // 定位"切稳定无效（武器种类/模式/传感器任一在客户端不成立）"。
            RVP_LeadFcDebug.logToggleRejected(unit);
            return false;
        }
        int allowedMask = resolveAllowedMask(unit);
        // 单模式子集（载具只配了一种火控模式）时整键静默：不切模式、不弹提示、不清半自动微调。
        // 2026-10-17 用户裁定：多弹种载具只配一种模式就是为了把该键位让给"切换弹种"，
        // 旧实现会原地"切换"同一模式并弹出模式提示（观感=瞬间切走又回退）+ 清掉微调偏置。
        if (Integer.bitCount(allowedMask) <= 1) {
            return false;
        }
        Mode mode = nextAllowedMode(getMode(unit), resolveAllowedMask(unit));
        MODES.put(unit, mode);
        // 调用本项目半自动微调状态，在任意模式切换时清除旧目标偏置，防止重新进入半自动后突然跳向旧方向。
        RVP_SemiAutoLeadTrimState.clear(unit);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable(switch (mode) {
                        case STABLE -> "message.ywzj_rvp.fire_control_mode.stable";
                        case SEMI_AUTO -> "message.ywzj_rvp.fire_control_mode.semi_auto";
                        case OFF -> "message.ywzj_rvp.fire_control_mode.off";
                    }),
                    true
            );
        }
        return true;
    }

    /**
     * 在开放子集内切换到下一个模式：按旧三态循环方向（STABLE→OFF→SEMI_AUTO，即枚举反序）
     * 轮转并用掩码过滤，只在子集内循环，单元素子集原地不动。纯静态便于单测复核循环次序与边界。
     */
    static Mode nextAllowedMode(Mode current, int allowedMask) {
        Mode[] order = Mode.values();
        for (int i = 1; i <= order.length; i++) {
            Mode next = order[((current.ordinal() - i) % order.length + order.length) % order.length];
            if ((allowedMask & (1 << next.ordinal())) != 0) {
                return next;
            }
        }
        return current;
    }

    /** 未知初始模式的选择：半自动优先，未被开放时取掩码中最低位的开放模式，掩码为空回退半自动。 */
    static Mode resolveInitialMode(int allowedMask) {
        if ((allowedMask & (1 << Mode.SEMI_AUTO.ordinal())) != 0) {
            return Mode.SEMI_AUTO;
        }
        for (Mode mode : Mode.values()) {
            if ((allowedMask & (1 << mode.ordinal())) != 0) {
                return mode;
            }
        }
        return Mode.SEMI_AUTO;
    }

    /**
     * 解析武器站 JSON {@code rvp_fire_control_modes} 数组为开放掩码；
     * 未配置（null/空列表）或全非法值时回退三种全有，与 hitl_video_modes 的兜底语义一致。
     */
    static int resolveAllowedMask(WeaponUnit unit) {
        if (!(unit.getData() instanceof org.ywzj.rvp.ext.WeaponUnitDataExt ext)) {
            return RVP_FireControlModeMask.ALL;
        }
        java.util.List<String> configured = ext.ywzj_rvp$getFireControlModes();
        if (configured == null || configured.isEmpty()) {
            return RVP_FireControlModeMask.ALL;
        }
        int mask = 0;
        for (String mode : configured) {
            mask |= RVP_FireControlModeMask.parse(mode);
        }
        return mask != 0 ? mask : RVP_FireControlModeMask.ALL;
    }

    private static boolean isEligible(WeaponUnit unit) {
        // 调用本项目武器解析器，解包Agent/Multi后确认当前实际武器种类。
        RVP_WeaponBase weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        boolean rvpMachinegun = weapon != null
                && weapon.getData().getWeaponKind() == RVP_EnumWeaponKind.MACHINEGUN;
        boolean rvpMissile = weapon != null
                && weapon.getData().getWeaponKind() == RVP_EnumWeaponKind.MISSILE;
        if (!(unit.getData() instanceof WeaponUnitDataExt ext)) {
            return false;
        }
        // 调用本项目传感器解析器，使武器级动态覆盖与三态切换资格保持一致。
        WeaponUnitData.FireControlSensorType sensorType = RVP_WeaponSensorHelper.effectiveSensorType(unit);
        return RVP_BallisticLeadFireControlPolicy.supportsStabilizer(
                ext.ywzj_rvp$getFireControlMode(),
                sensorType,
                rvpMachinegun,
                rvpMissile
        );
    }
}
