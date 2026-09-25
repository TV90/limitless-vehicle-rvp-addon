package org.ywzj.rvp.virtualflight.server;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_VirtualPresetGuidance;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_VirtualTrajectoryParameters;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * 把虚拟飞行业务配置适配为轨迹数学层的不可变输入。
 *
 * <p>该类刻意留在虚拟飞行业务包中，使 {@code guidance.trajectorymath.virtualguidance} 不依赖武器
 * JSON 数据模型。轨迹数学代码以后可由实体飞行、预测器或测试直接复用。</p>
 */
final class RVP_VirtualTrajectoryInputFactory {

    /** 工具类不允许实例化。 */
    private RVP_VirtualTrajectoryInputFactory() {
    }

    /**
     * 从当前武器配置冻结单 Tick 轨迹积分参数。
     *
     * @param data 当前武器配置
     * @param coldLaunchTimeTick 冷发射至少延迟到的点火 Tick
     * @param altitude 当前弹体世界 Y 高度，用于解析高度阻力系数
     * @param flightTick 当前即将积分的有效飞行 Tick，用于解析 turning_factor 区间
     * @return 与武器数据对象解耦的不可变积分参数
     */
    static RVP_VirtualTrajectoryParameters createParameters(RVP_WeaponData data,
                                                             int coldLaunchTimeTick,
                                                             double altitude,
                                                             int flightTick) {
        // 调用本项目武器数据访问器，集中冻结弹体物理配置，避免数学层持有可变配置对象。
        var projectile = data.getProjectileData();
        // 调用本项目弹体数据解析器，按虚拟态连续飞行 Tick 取得与实体态相同的方向插值值。
        Float configuredTurningFactor = projectile.resolveTurningFactor(flightTick);
        // 调用本项目弹体数据解析器，按当前飞行 Tick 解析点火后 Tick，再冻结本 Tick 的推力与质量
        // （含推力曲线 A2 与变质量 A1），使虚拟链与实体链使用完全相同的推进模型。
        int ignitionTick = Math.max(data.getResolvedIgnitionDelayTick(), coldLaunchTimeTick);
        int motorTick = flightTick - ignitionTick;
        float motorBurnTime = data.getResolvedMotorBurnTime();
        double resolvedThrust = (motorTick >= 0 && motorTick <= motorBurnTime)
                ? projectile.resolveThrustAt(motorTick)
                : 0f;
        double resolvedMass = projectile.resolveMassAt(motorTick, motorBurnTime);
        return new RVP_VirtualTrajectoryParameters(
                projectile.getRvpMaxG(),
                configuredTurningFactor != null ? configuredTurningFactor : 0.5F,
                data.getVirtualMidcourseData().getCruiseAltitude(),
                projectile.isRotateToMotion(),
                data.usesPropulsion(),
                resolvedMass,
                resolvedThrust,
                motorBurnTime,
                ignitionTick,
                data.getResolvedDragCoefficient(),
                projectile.resolveAltitudeDragFactor(altitude),
                data.getGravity(),
                projectile.getMinSpeed(),
                projectile.getMaxSpeed());
    }

    /**
     * 从当前武器配置冻结 PRESET 弹道制导输入。
     *
     * @param data 当前武器配置
     * @param launchPosition 固定发射点
     * @return 启用 PRESET 时返回不可变制导输入，否则返回 {@code null}
     */
    static RVP_VirtualPresetGuidance createPresetGuidance(RVP_WeaponData data,
                                                           Vec3 launchPosition) {
        // 调用本项目制导数据访问器，先判断 PRESET 是否启用，再向数学层传递标量快照。
        if (data == null || data.getGuidanceData() == null
                || data.getGuidanceData().getPresetCruiseAltitude() <= 0f) {
            return null;
        }
        var guidance = data.getGuidanceData();
        return new RVP_VirtualPresetGuidance(
                launchPosition,
                guidance.getPresetCruiseAltitude(),
                guidance.getPresetMaxAscentLead(),
                guidance.getPresetAscentRadius(),
                guidance.getPresetDiveRadius(),
                guidance.getPresetDiveAltitudeFactor(),
                guidance.getPresetDiveLeadFactor(),
                guidance.getPresetCruiseAltitudeGain(),
                guidance.getPresetCruiseVerticalDamping(),
                guidance.getPresetCruiseMaxVerticalComponent(),
                guidance.getPresetTacticalManeuverAmplitude());
    }
}
