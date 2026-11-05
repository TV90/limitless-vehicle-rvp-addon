package org.ywzj.rvp.client.firecontrol;

import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.rvp.client.state.RVP_SemiAutoLeadTrimState;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** 旧输入注入和新版吊舱共用的火控规则；不增加注入点，不改变普通站资格。 */
public final class RVP_ManualSightInput {
    private RVP_ManualSightInput() {}

    /** 按既有条件在全自动跟踪时抑制鼠标，不能只看模式而误伤未跟踪状态。 */
    public static boolean suppressed(WeaponUnit unit) {
        if (unit == null || LocalVehiclePlayer.instance == null
                || LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE
                || RVP_FireControlStabilizerState.getMode(unit) != RVP_FireControlStabilizerState.Mode.STABLE) return false;
        // 调用既有轻量资格解析，不在输入事件中运行弹道计算。
        if (RVP_MachinegunLeadSolver.isCurrentRvpMachinegun(unit)) return RVP_MachinegunLeadSolver.resolveTrackedTarget(unit) != null;
        // 调用原有 RF 硬锁规则，保留软锁时鼠标可动的行为。
        return RVP_RadarMissileTrackHelper.isEligible(unit) && RVP_RadarMissileTrackHelper.resolveHardLockedTarget(unit) != null;
    }

    /** 在写入目标前锁存俯仰微调，自动对齐不算玩家输入。 */
    public static void recordPitch(WeaponUnit unit, float requested, boolean manual) {
        if (manual && RVP_FireControlStabilizerState.getMode(unit) == RVP_FireControlStabilizerState.Mode.SEMI_AUTO) {
            // 调用原半自动状态，保持与原输入相同的局部机械偏置语义。
            RVP_SemiAutoLeadTrimState.recordPitchInput(unit, requested);
        }
    }

    /** 在写入目标前锁存偏航微调。 */
    public static void recordYaw(WeaponUnit unit, float requested, boolean manual) {
        if (manual && RVP_FireControlStabilizerState.getMode(unit) == RVP_FireControlStabilizerState.Mode.SEMI_AUTO) {
            // 调用原半自动状态，不能等真实角被覆盖后再计算差值。
            RVP_SemiAutoLeadTrimState.recordYawInput(unit, requested);
        }
    }
}
