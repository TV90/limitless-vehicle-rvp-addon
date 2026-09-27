package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;

/** RVP 推进弹体的燃烧阶段、推力、质量与速度增量共享计算。 */
public final class RVP_PropulsionMath {

    /** 工具类不允许实例化。 */
    private RVP_PropulsionMath() {
    }

    /**
     * 当前主发动机或第二脉冲阶段的冻结数据。
     *
     * @param burning 发动机是否在当前 Tick 提供推力
     * @param thrust 当前阶段推力，RVP 游戏单位
     * @param mass 当前阶段质量；推力积分沿用原值，阻力仅在质量小于 1 时换算为千克
     */
    public record MotorState(boolean burning, double thrust, double mass) {
    }

    /**
     * 根据点火后的燃烧 Tick 与第二脉冲状态解析当前发动机状态。
     * 主燃烧期使用推力曲线和变质量；第二脉冲与燃尽阶段使用主燃烧结束时的干质量。
     *
     * @param projectileData 当前武器的弹体推进数据
     * @param motorTick 点火后的 Tick；负值表示尚未点火
     * @param secondPulseBurning 调用方按触发时机解析出的第二脉冲燃烧状态
     * @return 当前阶段的推力、质量和燃烧标记；非燃烧阶段推力为 0
     */
    public static MotorState resolveMotorState(RVP_ProjectileData projectileData,
                                               int motorTick, boolean secondPulseBurning) {
        float primaryBurnTime = projectileData.getResolvedMotorBurnTime();
        boolean primaryBurning = motorTick >= 0 && motorTick <= primaryBurnTime;
        if (primaryBurning) {
            // 调用本项目弹体数据解析器：主燃烧期读取曲线推力与当前变质量。
            float thrust = projectileData.resolveThrustAt(motorTick);
            float mass = projectileData.resolveMassAt(motorTick, primaryBurnTime);
            return new MotorState(true, thrust, mass);
        }
        if (secondPulseBurning && projectileData.usesSecondPulse()) {
            // 调用本项目弹体数据解析器：第二脉冲使用配置推力和主燃料燃尽后的干质量。
            float thrust = projectileData.getResolvedSecondPulseThrust();
            float mass = projectileData.resolveMassAt(Math.round(primaryBurnTime), primaryBurnTime);
            return new MotorState(true, thrust, mass);
        }

        // 调用本项目弹体数据解析器：无推力阶段仍返回干质量，供阻力按正确质量计算。
        float dryMass = projectileData.resolveMassAt(Math.round(primaryBurnTime), primaryBurnTime);
        return new MotorState(false, 0.0F, dryMass);
    }

    /**
     * 把 RVP 游戏单位推力换算成单 Tick 加速度。
     *
     * @param thrust 发动机推力，RVP 游戏单位
     * @param mass 弹体质量，RVP 游戏单位
     * @return 加速度，单位格/Tick²；质量下限防止除零
     */
    public static double accelerationPerTick(double thrust, double mass) {
        return thrust / Math.max(mass, 1.0E-6D);
    }

    /**
     * 沿推力方向将发动机加速度积分到速度向量。
     *
     * @param velocity 当前速度，单位格/Tick
     * @param thrustDirection 当前发动机推力方向；非法或零方向时保持原速度
     * @param state 当前发动机推力与质量快照
     * @param tickFraction 本次积分占完整 Tick 的比例
     * @return 施加当前阶段推力后的速度
     */
    public static Vec3 applyThrust(Vec3 velocity, Vec3 thrustDirection,
                                   MotorState state, double tickFraction) {
        if (state == null || !state.burning() || !Double.isFinite(tickFraction) || tickFraction <= 0.0D
                || !Double.isFinite(state.thrust()) || state.thrust() <= 0.0F
                || !Double.isFinite(state.mass()) || state.mass() <= 0.0F
                || thrustDirection == null || !Double.isFinite(thrustDirection.lengthSqr())
                || thrustDirection.lengthSqr() <= 1.0E-12D) {
            return velocity;
        }
        return velocity.add(thrustDirection.normalize().scale(
                accelerationPerTick(state.thrust(), state.mass()) * tickFraction));
    }
}
