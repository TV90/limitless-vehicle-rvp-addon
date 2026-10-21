package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * PIP 专用的航迹到机头指令适配器，纯数学、无积分状态、不修改实体。
 * 外环沿用旧航迹响应和 G 预算，内环读取不可变双轴状态反解执行器，并计入沿机头推力。
 * 不预先扣诱导阻力；正式载荷与姿态只由返回的实际求解结果写回一次。
 */
public final class RVP_PIPAttackAngleController {
    /** 速度、方向和姿态响应的退化阈值。 */
    private static final double EPSILON = 1.0E-8;
    /** 单步攻角反解最多 INVERSE_ITERATIONS 轮，避免热路径随交战距离增长。 */
    private static final int INVERSE_ITERATIONS = 10;

    private RVP_PIPAttackAngleController() { }

    /**
     * 估计攻角双轴执行器在响应受限时需要的目标前视 Tick 数。
     *
     * <p>这不是把有效惯量当作真实刚体参数，而是把已有的姿态响应和质量降阶系数转换为
     * 很小的有限时域补偿；仅在实际 {@link Result#responseLimited()} 时由外层调用，避免
     * 正常状态对恒速 PIP 额外加提前量。</p>
     */
    public static double estimateResponseLeadTicks(Vec3 velocity,
                                                    RVP_AeroSteeringLimits limits,
                                                    double mass) {
        if (!validDirection(velocity) || limits == null || !limits.attackAngleEnabled()) {
            return 0.0D;
        }
        double response = Mth.clamp(
                RVP_AttackAngleInertiaModel.response(velocity.length(), limits), 0.0D, 1.0D);
        double inertia = RVP_AttackAngleInertiaModel.effectiveInertia(mass);
        return Mth.clamp(1.0D + inertia * (1.0D - response), 0.0D, 3.0D);
    }

    /**
     * @param steering 本 Tick 实际姿态响应与攻角裁决结果，尚未写入实体
     * @param bodyCommand 送入原攻角求解器的机头指令方向
     * @param requestedVelocity 外环希望达到的速度方向及原速率
     * @param predictedVelocity 实际姿态响应后叠加本 Tick 推力的速度；仅供诊断
     * @param requestedTurnRadians 外环裁决后请求的单 Tick 航迹转角，单位弧度
     * @param responseLimited 机头本 Tick 无法到达反解姿态，已按原响应可达范围限幅
     */
    public record Result(RVP_AttackAngleSolution steering, Vec3 bodyCommand,
                         Vec3 requestedVelocity, Vec3 predictedVelocity,
                         double requestedTurnRadians, boolean responseLimited) { }

    /**
     * 反解 PIP 航迹请求；重力和阻力仍由运动阶段处理，本层只补偿控制器与轴向推力。
     *
     * @param velocity 当前速度，格/Tick
     * @param bodyDirection 当前独立机头轴
     * @param desiredDirection PIP 期望航迹方向，不预插值
     * @param limits 本 Tick 与实体共用的气动预算
     * @param motor 本 Tick 已冻结的发动机状态，不得在试算中触发点火或消耗燃料
     * @return 无副作用的单 Tick 实际求解及诊断数据
     */
    public static Result solve(Vec3 velocity, Vec3 bodyDirection, Vec3 desiredDirection,
                               RVP_AeroSteeringLimits limits, RVP_PropulsionMath.MotorState motor) {
        // 调用状态构造器提供零角速度的独立预测入口；连续仿真与实体改用显式状态重载。
        Vec3 body = validDirection(bodyDirection) ? bodyDirection
                : validDirection(velocity) ? velocity : new Vec3(0, 0, 1);
        return solve(velocity, RVP_AttackAngleState.fromDirection(body, 0), desiredDirection,
                limits, motor, motor == null ? 0.0 : motor.mass());
    }

    /** 读取当前角速度及惯量质量的无副作用入口；试算始终从同一个输入状态开始。 */
    public static Result solve(Vec3 velocity, RVP_AttackAngleState state, Vec3 desiredDirection,
            RVP_AeroSteeringLimits limits, RVP_PropulsionMath.MotorState motor, double mass) {
        // 调用本项目输入判据；非攻角分支不应使用此控制器，坏输入按原求解器安全退化。
        if (!validDirection(velocity) || !validDirection(desiredDirection)
                || limits == null || !limits.attackAngleEnabled()) {
            var unchanged = RVP_AttackAngleModel.solve(velocity, state, desiredDirection, limits, mass);
            return new Result(unchanged, unchanged.bodyDirection(), unchanged.velocity(), unchanged.velocity(), 0.0, false);
        }
        double speed = velocity.length();
        Vec3 axis = velocity.normalize();
        // 调用本项目有限方向判据，使控制器和执行器对缺失机头采用同一速度轴回退。
        // 调用双轴状态极点脱离入口：PIP 首次给出水平指令时沿目标水平投影选定转弯平面。
        state = state.sanitized().reanchorPoleYaw(desiredDirection);
        // 调用本项目旧航迹插值作为外环请求；显式 G 下保留完整期望方向。
        Vec3 request = limits.rvpMaxGs() != null ? desiredDirection
                : RVP_TrajectorySteeringMath.applyTurningFactor(
                        velocity, desiredDirection, speed, limits.turningFactor());
        // 调用本项目气动裁决，仅取得外环目标，不把此速度直接写入实体。
        Vec3 requestedVelocity = RVP_AeroSteeringModel.solve(velocity, request, limits).velocity();
        Vec3 requestedAxis = requestedVelocity.normalize();
        // 调用本项目夹角与确定性球面工具，建立包括反向输入在内的转弯平面。
        double requestedTurn = RVP_BallisticTrajectoryMath.angleBetween(axis, requestedAxis);
        Vec3 tangent = tangentToward(axis, requestedAxis);
        double low = 0.0;
        double high = Math.toRadians(limits.attackAngleLimitDeg());
        if (requestedTurn <= EPSILON) {
            high = 0.0;
        } else {
            for (int iteration = 0; iteration < INVERSE_ITERATIONS; iteration++) {
                double alpha = (low + high) * 0.5;
                // 调用本类方向构造和前向试算，只读取现有模型；不写实体、载荷或发动机状态。
                Vec3 candidate = directionAt(axis, tangent, alpha);
                Vec3 predicted = predictSettledBody(velocity, candidate, limits, motor);
                double turn = Math.atan2(predicted.dot(tangent), predicted.dot(axis));
                if (turn < requestedTurn) low = alpha;
                else high = alpha;
            }
        }
        // 调用本类方向构造，取得达到请求所需的机头姿态；能力不足时自然饱和到攻角锥。
        Vec3 desiredBody = directionAt(axis, tangent, (low + high) * 0.5);
        // 调用共用增益和角度转换反解双轴执行器，显式扣除上一 Tick 尚未消散的角速度。
        double response = RVP_AttackAngleInertiaModel.response(speed, limits);
        double gain = RVP_AttackAngleInertiaModel.rateResponse(mass);
        var target = RVP_AttackAngleState.fromDirection(desiredBody, state.yawDeg());
        double pitchStep = target.pitchDeg() - state.pitchDeg();
        double yawStep = Mth.wrapDegrees(target.yawDeg() - state.yawDeg());
        double pitchCommand = response <= EPSILON ? state.pitchDeg() : state.pitchDeg()
                + (pitchStep - (1.0 - gain) * state.pitchRateDeg()) / (gain * response);
        double yawCommand = response <= EPSILON ? state.yawDeg() : state.yawDeg()
                + (yawStep - (1.0 - gain) * state.yawRateDeg()) / (gain * response);
        // 两轴反解只提供指令，不绕过实际攻角锥/角速度限制；过大的偏航误差不能环绕为反向指令。
        Vec3 command = new RVP_AttackAngleState(Mth.clamp(pitchCommand, -90, 90),
                state.yawDeg() + Mth.clamp(yawCommand - state.yawDeg(), -179.9, 179.9), 0, 0).direction();
        // 调用真实攻角模型，以当前机头执行一次实际响应；与上面假定已达姿态的试算区分。
        var steering = RVP_AttackAngleModel.solve(velocity, state, command, limits, mass);
        // 逆解超出可达域时，欧拉角外推可能跨越半球；补试同转弯平面的锥边指令，
        // 仅选择更接近目标姿态的结果。两个试算都读取原始状态，绝不累计积分。
        Vec3 saturatedCommand = directionAt(axis, tangent, Math.toRadians(limits.attackAngleLimitDeg()));
        var saturated = RVP_AttackAngleModel.solve(velocity, state, saturatedCommand, limits, mass);
        if (RVP_BallisticTrajectoryMath.angleBetween(saturated.bodyDirection(), desiredBody)
                < RVP_BallisticTrajectoryMath.angleBetween(steering.bodyDirection(), desiredBody)) {
            command = saturatedCommand;
            steering = saturated;
        }
        // 调用实际姿态误差判据标记不可达请求，不能把理想反解当作已实现的转弯。
        boolean limited = RVP_BallisticTrajectoryMath.angleBetween(steering.bodyDirection(), desiredBody) > 1.0E-4;
        // 调用本项目推力工具预估运动阶段额外转向；返回值只用于诊断，不提前施加推力。
        Vec3 predicted = RVP_PropulsionMath.applyThrust(steering.velocity(), steering.bodyDirection(), motor, 1.0);
        return new Result(steering, command, requestedVelocity, predicted, requestedTurn, limited);
    }

    /** 试算指定机头已可达时的气动力与沿机头推力，复用实际执行器以防公式漂移。 */
    private static Vec3 predictSettledBody(Vec3 velocity, Vec3 body, RVP_AeroSteeringLimits limits,
                                           RVP_PropulsionMath.MotorState motor) {
        // 调用本项目攻角模型；起始和目标机头相同，单独隔离升力响应供攻角反解。
        var steering = RVP_AttackAngleModel.evaluateBody(velocity,
                RVP_AttackAngleState.fromDirection(body, 0), limits);
        // 调用本项目推进公式，计入推力法向分量，避免动力段重复给足气动修正。
        return RVP_PropulsionMath.applyThrust(steering.velocity(), steering.bodyDirection(), motor, 1.0);
    }

    /** 构造单位轴向与单位切向平面中的方向，支持有界外推而非只做内插。 */
    private static Vec3 directionAt(Vec3 axis, Vec3 tangent, double angle) {
        return axis.scale(Math.cos(angle)).add(tangent.scale(Math.sin(angle))).normalize();
    }

    /** 共线/反向时复用已有 slerp 的确定性转向平面，避免除以零或随机翻面。 */
    private static Vec3 tangentToward(Vec3 axis, Vec3 desired) {
        Vec3 tangent = desired.subtract(axis.scale(Mth.clamp(axis.dot(desired), -1.0, 1.0)));
        if (tangent.lengthSqr() > EPSILON * EPSILON) return tangent.normalize();
        // 调用本项目反向球面插值，得到确定性的正交方向。
        return RVP_BallisticTrajectoryMath.slerpDirection(axis, axis.reverse(), 0.5);
    }

    /** 与攻角模型一致的有效非零方向判据。 */
    private static boolean validDirection(Vec3 value) {
        // 调用本项目有限值检查，拒绝 NaN/Infinity。
        return value != null && RVP_BallisticTrajectoryMath.isFinite(value)
                && value.lengthSqr() > EPSILON * EPSILON;
    }
}
