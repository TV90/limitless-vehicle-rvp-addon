package org.ywzj.rvp.client.lead;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientBroadcastVehicleInterpolator;
import org.ywzj.rvp.debug.RVP_WeaponOriginDebug;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

/** 负责把客户端追踪样本接入双端安全的机炮弹道求解器。 */
public final class RVP_ClientMachinegunLeadResolver {
    private RVP_ClientMachinegunLeadResolver() {}

    /**
     * 解算当前机炮对当前锁定目标的提前量。
     *
     * <p>超过本体同步距离的载具使用 RVP 广播样本插值；普通实体仍沿用实体位置历史估速。</p>
     */
    @Nullable
    public static RVP_LeadSolution solveCurrent(WeaponUnit controlWeaponUnit, float partialTick) {
        // 调用本项目机炮求解器解析当前选中武器数据，排除非机炮武器。
        RVP_WeaponData data = RVP_MachinegunLeadSolver.resolveCurrentWeaponData(controlWeaponUnit);
        if (data == null) {
            return null;
        }
        // 调用本项目机炮求解器统一解析直连、外部雷达链路与主雷达锁定目标。
        Entity target = RVP_MachinegunLeadSolver.resolveTrackedTarget(controlWeaponUnit);
        if (target == null) {
            return null;
        }
        // 调用本项目机炮求解器取得实际挂载武器站，保证解算原点与弹体生成使用同一门炮。
        WeaponUnit launchWeaponUnit = RVP_MachinegunLeadSolver.resolveCurrentLaunchWeaponUnit(controlWeaponUnit);
        if (launchWeaponUnit == null) {
            return null;
        }
        // 调用本体实际挂载单元的瞄准上下文，读取本 tick 的真实炮口世界坐标。
        AimContext launchAim = launchWeaponUnit.aimContext();
        Vec3 muzzle = launchAim == null ? null : launchAim.from;
        if (muzzle == null) {
            // 调用本体炮闩坐标，作为实际挂载单元瞄准上下文缺失时的安全回退。
            muzzle = launchWeaponUnit.worldCurrentBoltPosition();
        }

        // 调用本项目广播载具插值器，使机炮火控与 HUD、导弹火控共用同一目标时间线。
        RVP_ClientBroadcastVehicleInterpolator.TargetTrackingSample trackingSample =
                RVP_ClientBroadcastVehicleInterpolator.resolveTrackingSample(target, partialTick);
        RVP_LeadSolution solution;
        if (trackingSample.broadcastVehicle()) {
            Vec3 targetVelocity = RVP_MachinegunLeadSolver.combineAndFilterTargetVelocity(
                    trackingSample.velocity(), trackingSample.velocity(), target.onGround());
            solution = RVP_MachinegunLeadSolver.solveForTargetMotion(
                    launchWeaponUnit,
                    data,
                    muzzle,
                    target,
                    trackingSample.center(),
                    targetVelocity,
                    trackingSample.bufferDelayTicks()
            );
        } else {
            // 调用双端安全的普通实体解算入口，保留既有位置历史估速与 AHEAD 服务端行为。
            solution = RVP_MachinegunLeadSolver.solveForTarget(
                    launchWeaponUnit, data, muzzle, target, partialTick);
        }
        // 调用本项目既有 weaponorigin 详细日志，在用户显式开启时记录解算与真实发射单元的对应关系。
        RVP_WeaponOriginDebug.noteLeadSolution(
                controlWeaponUnit, launchWeaponUnit, target, muzzle, solution);
        return solution;
    }
}
