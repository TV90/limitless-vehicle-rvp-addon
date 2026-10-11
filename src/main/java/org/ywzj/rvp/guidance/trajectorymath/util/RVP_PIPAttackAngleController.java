package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * PIP 专用的航迹到机头指令适配器，纯数学、无积分状态、不修改实体。
 * 外环沿用旧航迹响应和 G 预算，内环反解现有攻角执行器并计入本 Tick 沿机头推力。
 * 不预先扣诱导阻力；正式载荷与姿态只由返回的实际求解结果写回一次。
 */
public final class RVP_PIPAttackAngleController {
    /** 速度、方向和姿态响应的退化阈值。 */
    private static final double EPSILON = 1.0E-8;
    /** 单步攻角反解最多 INVERSE_ITERATIONS 轮，避免热路径随交战距离增长。 */
    private static final int INVERSE_ITERATIONS = 10;
    /** 避开共用 slerp 的完全反向分支，保留期望旋转平面，单位弧度。 */
    private static final double MAX_INPUT_ANGLE = Math.PI - 0.003;

    private RVP_PIPAttackAngleController() { }

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
        // 调用本项目输入判据；非攻角分支不应使用此控制器，坏输入按原求解器安全退化。
        if (!validDirection(velocity) || !validDirection(desiredDirection)
                || limits == null || !limits.attackAngleEnabled()) {
            var unchanged = RVP_AttackAngleModel.solve(velocity, bodyDirection, desiredDirection, limits);
            return new Result(unchanged, unchanged.bodyDirection(), unchanged.velocity(), unchanged.velocity(), 0.0, false);
        }
        double speed = velocity.length();
        Vec3 axis = velocity.normalize();
        // 调用本项目有限方向判据，使控制器和执行器对缺失机头采用同一速度轴回退。
        Vec3 body = validDirection(bodyDirection) ? bodyDirection.normalize() : axis;
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
        // 调用原模型响应比例与夹角算法，反解 slerp 输入，不能把攻角再无条件乘一次 factor。
        double response = RVP_AttackAngleModel.bodyResponse(limits);
        double bodyStep = RVP_BallisticTrajectoryMath.angleBetween(body, desiredBody);
        boolean limited = response <= EPSILON || bodyStep > MAX_INPUT_ANGLE * response;
        // 调用本类确定性平面与方向构造；请求超过响应可达性时限幅，不产生积分累积。
        Vec3 command = response <= EPSILON ? body : directionAt(body, tangentToward(body, desiredBody),
                Math.min(bodyStep / response, MAX_INPUT_ANGLE));
        // 调用真实攻角模型，以当前机头执行一次实际响应；与上面假定已达姿态的试算区分。
        var steering = RVP_AttackAngleModel.solve(velocity, body, command, limits);
        // 调用本项目推力工具预估运动阶段额外转向；返回值只用于诊断，不提前施加推力。
        Vec3 predicted = RVP_PropulsionMath.applyThrust(steering.velocity(), steering.bodyDirection(), motor, 1.0);
        return new Result(steering, command, requestedVelocity, predicted, requestedTurn, limited);
    }

    /** 试算指定机头已可达时的气动力与沿机头推力，复用实际执行器以防公式漂移。 */
    private static Vec3 predictSettledBody(Vec3 velocity, Vec3 body, RVP_AeroSteeringLimits limits,
                                           RVP_PropulsionMath.MotorState motor) {
        // 调用本项目攻角模型；起始和目标机头相同，单独隔离升力响应供攻角反解。
        var steering = RVP_AttackAngleModel.solve(velocity, body, body, limits);
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
