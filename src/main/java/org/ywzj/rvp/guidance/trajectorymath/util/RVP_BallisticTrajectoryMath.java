package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 无世界状态、无实体状态的弹道轨迹数学工具。
 *
 * <p>所有方法只根据传入的不可变向量和标量返回计算结果，不保存跨 Tick 状态。调用方可将
 * 这些方法复用于虚拟弹道积分、实体弹道预测、瞄准预览和纯数学测试。</p>
 */
public final class RVP_BallisticTrajectoryMath {
    /** 世界 Y 高度误差转换为垂直速度指令的比例增益。 */
    private static final double CRUISE_ALTITUDE_GAIN = 0.015;
    /** 当前垂直速度的阻尼系数，用于抑制巡航高度附近振荡。 */
    private static final double CRUISE_VERTICAL_DAMPING = 0.05;
    /** 垂直指令相对当前总速率的绝对上限。 */
    private static final double CRUISE_MAX_VERTICAL_COMPONENT = 0.5;
    /** 末端可达性判断额外保留的直线飞行 Tick。 */
    private static final double TERMINAL_RESERVE_TICKS = 2.0;

    /** 工具类不允许实例化。 */
    private RVP_BallisticTrajectoryMath() {
    }

    /**
     * 按虚拟弹道参数依次施加点火后的推力、速度平方阻力和重力。
     *
     * @param velocity Tick 开始时的速度，单位格/Tick
     * @param flightTick 当前即将完成的飞行 Tick
     * @param ignitionTick 发射后开始点火的飞行 Tick
     * @param propulsion 是否启用发动机推力
     * @param motorBurnTime 主发动机从点火起持续的 Tick 数
     * @param thrust 每 Tick 沿当前速度方向施加的推力
     * @param mass 弹体质量，必须与推力使用相同单位制
     * @param dragCoefficient 速度平方阻力系数
     * @param altitudeDragFactor 当前高度对应的阻力倍率
     * @param gravity 每 Tick 施加的 Y 轴重力增量；零值使用本体默认重力常量
     * @return 施加各项力后的新速度
     */
    public static Vec3 integrateForces(Vec3 velocity, int flightTick, int ignitionTick,
                                       boolean propulsion, double motorBurnTime,
                                       double thrust, double mass,
                                       double dragCoefficient, double altitudeDragFactor,
                                       double gravity) {
        if (flightTick < ignitionTick) {
            return velocity;
        }

        int motorTick = flightTick - ignitionTick;
        if (propulsion && motorTick <= motorBurnTime) {
            Vec3 direction = velocity.lengthSqr() > 1.0E-8
                    ? velocity.normalize()
                    : new Vec3(0.0, 1.0, 0.0);
            // 调用本项目推力加速度换算，保证实体链/虚拟链单位一致（A3）。
            double acceleration = thrustAccelerationPerTick(thrust, mass);
            velocity = velocity.add(direction.scale(acceleration));
        }

        double speedSqr = velocity.lengthSqr();
        double drag = dragCoefficient * altitudeDragFactor;
        if (speedSqr > 1.0E-12 && drag > 0.0) {
            velocity = velocity.add(velocity.normalize().scale(-drag * speedSqr));
        }
        return gravity != 0.0
                ? velocity.add(0.0, gravity, 0.0)
                : velocity.subtract(0.0, PhysicsEngine.G, 0.0);
    }

    /**
     * RVP 推力加速度换算（RVP 游戏单位制）。
     *
     * <p>RVP 的 {@code thrust} 与 {@code mass} 采用游戏调优单位：每 Tick 加速度（格/Tick²）
     * 直接等于 {@code thrust / mass}，<b>不</b> 再除以 {@code TICKS_PER_SECOND_SQUARED}。
     * 这与本体 {@link org.ywzj.vehicle.util.PhysicsHelper#accelerationPerTick(double, double)}
     * （牛顿/千克，需再除 400）刻意不同，使 RVP JSON 的 mass/thrust 保持小数值、便于调参。
     * 实体链与虚拟链必须统一经本方法换算，避免两套推进模型漂移。</p>
     *
     * @param thrust 发动机推力（RVP 游戏单位）
     * @param mass 弹体质量（RVP 游戏单位，内部钳制到最小 1e-6 防止除零）
     * @return 每 Tick 推力加速度，单位格/Tick²
     */
    public static double thrustAccelerationPerTick(double thrust, double mass) {
        return thrust / Math.max(mass, 1.0E-6);
    }

    /**
     * 按最大法向过载把当前速度方向转向期望方向，同时严格保持输入速率。
     *
     * <p>单 Tick 允许的速度向量变化量为 {@code maxGs * PhysicsEngine.G}。先把该弦长
     * 换算为最大转角，再在两个单位方向间做球面插值，因此结果同时满足速率不变和
     * G 值上限。零速、非法方向或非正 maxGs 均保持原速度。</p>
     *
     * @param velocity 当前速度向量，单位格/Tick
     * @param desiredDirection 期望飞行方向；允许传入未归一化向量
     * @param maxGs 最大法向过载，单位 G
     * @return 经过 G 值钳制的新速度向量，长度与 {@code velocity} 相同
     */
    public static Vec3 applySteering(Vec3 velocity, Vec3 desiredDirection, double maxGs) {
        double speed = velocity.length();
        if (!isFinite(velocity) || desiredDirection == null || !isFinite(desiredDirection)
                || speed <= 1.0E-8 || desiredDirection.lengthSqr() <= 1.0E-12
                || !Double.isFinite(maxGs) || maxGs <= 0.0) {
            return velocity;
        }
        Vec3 current = velocity.scale(1.0 / speed);
        Vec3 desired = desiredDirection.normalize();
        double angle = angleBetween(current, desired);
        if (angle <= 1.0E-12) {
            return velocity;
        }

        double chordRatio = Mth.clamp(maxGs * PhysicsEngine.G / (2.0 * speed), 0.0, 1.0);
        double maxTurn = 2.0 * Math.asin(chordRatio);
        if (maxTurn >= angle) {
            return desired.scale(speed);
        }
        return slerpDirection(current, desired, maxTurn / angle).scale(speed);
    }

    /**
     * 按弹体配置选择唯一的转向钳制算法。
     *
     * <p>{@code rvpMaxGs} 非 null 时必须调用 G 值 {@link #applySteering(Vec3, Vec3, double)}，
     * 并忽略同时存在的 {@code turningFactor}；未配置 G 值时调用从实体制导链复制出的
     * {@link RVP_TrajectorySteeringMath#applyTurningFactor(Vec3, Vec3, double, float)}。</p>
     *
     * @param velocity 当前速度，单位格/Tick
     * @param desiredDirection 期望方向；允许传入未归一化向量
     * @param rvpMaxGs 可选 RVP 最大法向过载，单位 G；null 表示未配置
     * @param turningFactor 未配置 RVP 最大法向过载时使用的方向插值强度
     * @return 经过选定算法钳制、且保持输入速率的新速度
     */
    public static Vec3 applyConfiguredSteering(Vec3 velocity, Vec3 desiredDirection,
                                                Double rvpMaxGs, float turningFactor) {
        if (rvpMaxGs != null) {
            return applySteering(velocity, desiredDirection, rvpMaxGs);
        }
        double speed = velocity == null ? 0.0 : velocity.length();
        return RVP_TrajectorySteeringMath.applyTurningFactor(
                velocity, desiredDirection, speed, turningFactor);
    }

    /**
     * 为 GPS 巡航生成“水平指向目标 + 高度闭环”的候选速度，并检查末端是否可达。
     *
     * <p>只有候选巡航路线仍能以当前最大 G 值接入目标时才保持高度，否则立即退化为直接
     * 追踪目标，使命中目标的优先级高于巡航高度。</p>
     *
     * @param position 当前世界坐标
     * @param velocity 当前速度，单位格/Tick
     * @param target 固定 GPS 目标世界坐标
     * @param configuredCruiseAltitude 可选世界 Y 巡航高度；为空时以当前高度为闭环基准
     * @param maxGs 最大法向过载，单位 G
     * @return 保持当前速率、方向变化不超过最大 G 值的新速度
     */
    public static Vec3 steerGpsCruise(Vec3 position, Vec3 velocity, Vec3 target,
                                      Double configuredCruiseAltitude, double maxGs) {
        return steerGpsCruise(
                position, velocity, target, configuredCruiseAltitude, maxGs, 0.5F);
    }

    /**
     * 为 GPS 巡航生成候选速度，并按弹体配置选择 G 值或方向插值转向约束。
     *
     * @param position 当前世界坐标
     * @param velocity 当前速度，单位格/Tick
     * @param target 固定 GPS 目标世界坐标
     * @param configuredCruiseAltitude 可选世界 Y 巡航高度
     * @param rvpMaxGs 可选 RVP 最大法向过载，单位 G；非 null 时优先
     * @param turningFactor 未配置 RVP 最大法向过载时使用的方向插值强度
     * @return 保持当前速率并受选定转向算法约束的新速度
     */
    public static Vec3 steerGpsCruise(Vec3 position, Vec3 velocity, Vec3 target,
                                      Double configuredCruiseAltitude, Double rvpMaxGs,
                                      float turningFactor) {
        double speed = velocity.length();
        if (speed <= 1.0E-8) {
            return velocity;
        }
        Vec3 directTargetDelta = target.subtract(position);
        if (directTargetDelta.length() <= speed * (TERMINAL_RESERVE_TICKS + 1.0)) {
            return applyConfiguredSteering(
                    velocity, directTargetDelta, rvpMaxGs, turningFactor);
        }

        Vec3 horizontalDelta = new Vec3(target.x - position.x, 0.0, target.z - position.z);
        if (horizontalDelta.lengthSqr() <= 1.0E-12) {
            return applyConfiguredSteering(
                    velocity, target.subtract(position), rvpMaxGs, turningFactor);
        }
        Vec3 horizontalDesired = horizontalDelta.normalize();
        double cruiseAltitude = configuredCruiseAltitude == null
                ? position.y
                : configuredCruiseAltitude;
        double altitudeError = cruiseAltitude - position.y;

        double maxVerticalCommand = speed * CRUISE_MAX_VERTICAL_COMPONENT;
        double verticalComponent = altitudeError * CRUISE_ALTITUDE_GAIN
                - velocity.y * CRUISE_VERTICAL_DAMPING;
        double verticalCommand = Mth.clamp(
                verticalComponent, -maxVerticalCommand, maxVerticalCommand);
        Vec3 desired = new Vec3(
                horizontalDesired.x,
                verticalCommand / speed,
                horizontalDesired.z).normalize();
        Vec3 cruiseVelocity = applyConfiguredSteering(
                velocity, desired, rvpMaxGs, turningFactor);
        Vec3 candidatePosition = position.add(cruiseVelocity);
        if (canReachTarget(
                candidatePosition, cruiseVelocity, target,
                rvpMaxGs, turningFactor, TERMINAL_RESERVE_TICKS)) {
            return cruiseVelocity;
        }
        return applyConfiguredSteering(
                velocity, directTargetDelta, rvpMaxGs, turningFactor);
    }

    /**
     * 为 PRESET 弹道生成抛物线中段或终端俯冲速度。
     *
     * <p>段判定不保存状态，末端转向统一使用 {@link #applySteering(Vec3, Vec3, double)}
     * 执行最大 G 值钳制，保证调用方可以仅凭当前几何状态复算结果。</p>
     *
     * @param position 当前世界坐标
     * @param velocity 当前速度，单位格/Tick
     * @param target 固定目标世界坐标
     * @param preset PRESET 弹道的冻结参数
     * @param maxGs 最大法向过载，单位 G
     * @return 保持当前速率、方向变化不超过最大 G 值的新速度
     */
    public static Vec3 steerPresetBallistic(Vec3 position, Vec3 velocity, Vec3 target,
                                            RVP_BallisticTrajectoryProfile preset, double maxGs) {
        return steerPresetBallistic(position, velocity, target, preset, maxGs, 0.5F);
    }

    /**
     * 为 PRESET 弹道生成受弹体 G 值或方向插值参数约束的新速度。
     *
     * @param position 当前世界坐标
     * @param velocity 当前速度，单位格/Tick
     * @param target 固定目标世界坐标
     * @param preset PRESET 弹道冻结参数
     * @param rvpMaxGs 可选 RVP 最大法向过载，单位 G；非 null 时优先
     * @param turningFactor 未配置 RVP 最大法向过载时使用的方向插值强度
     * @return 保持当前速率并受选定转向算法约束的新速度
     */
    public static Vec3 steerPresetBallistic(Vec3 position, Vec3 velocity, Vec3 target,
                                            RVP_BallisticTrajectoryProfile preset,
                                            Double rvpMaxGs, float turningFactor) {
        double speed = velocity.length();
        if (speed <= 1.0E-8 || target == null || preset == null
                || preset.launchPosition() == null) {
            return velocity;
        }
        // 通过通用只读参数契约取得纯几何计算所需的冻结值，不依赖虚拟飞行业务类型。
        Vec3 launch = preset.launchPosition();

        double turnRadius = resolveTurnRadius(speed, rvpMaxGs, turningFactor);
        double diveDistance = Math.max(preset.diveRadius(), Math.max(
                Math.max(0.0, position.y - target.y) * preset.diveAltitudeFactor(),
                turnRadius * preset.diveLeadFactor()));
        double dx = position.x - target.x;
        double dz = position.z - target.z;
        boolean diving = dx * dx + dz * dz <= diveDistance * diveDistance;
        if (!diving) {
            Vec3 route = new Vec3(target.x - launch.x, 0.0, target.z - launch.z);
            Vec3 remaining = new Vec3(target.x - position.x, 0.0, target.z - position.z);
            diving = remaining.dot(route) <= 0.0;
        }

        if (diving) {
            Vec3 toTarget = target.subtract(position);
            double horizontalSqr = toTarget.x * toTarget.x + toTarget.z * toTarget.z;
            Vec3 velocityHorizontal = new Vec3(velocity.x, 0.0, velocity.z);
            double velocityHorizontalSqr = velocityHorizontal.lengthSqr();
            boolean overshoot = horizontalSqr > 1.0E-8 && velocityHorizontalSqr > 1.0E-8
                    && velocityHorizontal.dot(toTarget) < 0.0;
            boolean veryClose = horizontalSqr <= Math.max(1.0, speed * speed * 0.25);
            Vec3 desired;
            if (overshoot || veryClose) {
                desired = velocityHorizontalSqr > 1.0E-8
                        ? velocityHorizontal.scale(0.25 / Math.sqrt(velocityHorizontalSqr))
                                .add(0.0, -1.0, 0.0)
                        : new Vec3(0.0, -1.0, 0.0);
            } else {
                desired = toTarget;
            }
            return applyConfiguredSteering(velocity, desired, rvpMaxGs, turningFactor);
        }

        Vec3 toTargetHorizontal = new Vec3(
                target.x - position.x, 0.0, target.z - position.z);
        double horizontalDistance = toTargetHorizontal.length();
        if (horizontalDistance <= 1.0E-8) {
            return applyConfiguredSteering(
                    velocity, target.subtract(position), rvpMaxGs, turningFactor);
        }

        Vec3 forwardHorizontal = toTargetHorizontal.normalize();
        double totalHorizontalDistance = horizontalDistance;
        double totalDx = target.x - launch.x;
        double totalDz = target.z - launch.z;
        double totalDistanceSqr = totalDx * totalDx + totalDz * totalDz;
        if (totalDistanceSqr > 1.0E-8) {
            totalHorizontalDistance = Math.sqrt(totalDistanceSqr);
        }
        double progress = Mth.clamp(
                1.0 - horizontalDistance / totalHorizontalDistance, 0.0, 1.0);
        double apogee = Math.min(preset.cruiseAltitude(), totalHorizontalDistance * 0.35);
        double base = Math.min(40.0, apogee * 0.3);
        double targetY = launch.y + base
                + (apogee - base) * 4.0 * progress * (1.0 - progress);
        double lookAhead = Math.min(preset.maxAscentLead(), horizontalDistance * 0.3);
        Vec3 targetPoint = new Vec3(
                position.x + forwardHorizontal.x * lookAhead,
                targetY,
                position.z + forwardHorizontal.z * lookAhead);

        double maneuverAmplitude = preset.tacticalManeuverAmplitude();
        if (maneuverAmplitude > 0.0) {
            double fadeIn = Mth.clamp((progress - 0.15) / 0.05, 0.0, 1.0);
            double fadeOut = Mth.clamp((0.85 - progress) / 0.05, 0.0, 1.0);
            double weight = fadeIn * fadeOut;
            if (weight > 1.0E-4) {
                double phase = Math.PI * 2.0 * progress * 5.0;
                Vec3 lateral = new Vec3(
                        -forwardHorizontal.z, 0.0, forwardHorizontal.x);
                targetPoint = targetPoint.add(
                        lateral.scale(maneuverAmplitude * Math.sin(phase) * weight));
            }
        }
        return applyConfiguredSteering(
                velocity, targetPoint.subtract(position), rvpMaxGs, turningFactor);
    }

    /**
     * 由 G 值钳制推导近似最小转弯半径。
     *
     * @param speed 当前速率，单位格/Tick
     * @param maxGs 最大法向过载，单位 G
     * @return 钳制在 8～80 格内的近似转弯半径
     */
    public static double resolveTurnRadius(double speed, double maxGs) {
        if (!Double.isFinite(maxGs) || maxGs <= 0.0) {
            return 8.0;
        }
        double maxDeltaVelocity = maxGs * PhysicsEngine.G;
        if (maxDeltaVelocity >= 2.0 * speed) {
            return 8.0;
        }
        return Mth.clamp(speed * speed / maxDeltaVelocity, 8.0, 80.0);
    }

    /**
     * 按实际启用的转向算法估算最小转弯半径，供虚拟路线可达性判定使用。
     *
     * @param speed 当前速率，单位格/Tick
     * @param rvpMaxGs 可选 RVP 最大法向过载，单位 G；非 null 时优先
     * @param turningFactor 未配置 RVP 最大法向过载时使用的方向插值强度
     * @return 钳制在 8～80 格内的近似转弯半径；完全禁止转向时返回正无穷
     */
    public static double resolveTurnRadius(double speed, Double rvpMaxGs,
                                           float turningFactor) {
        if (rvpMaxGs != null) {
            return resolveTurnRadius(speed, rvpMaxGs.doubleValue());
        }
        double factor = Mth.clamp(turningFactor, 0.0F, 1.0F);
        if (factor <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return Mth.clamp(speed / factor, 8.0, 80.0);
    }

    /**
     * 评估当前姿态能否在最大 G 值限制下、不绕回目标后方地接入目标点。
     *
     * @param position 评估起点的世界坐标
     * @param velocity 评估起点的速度，单位格/Tick
     * @param target 固定目标世界坐标
     * @param maxGs 最大法向过载，单位 G
     * @param reserveTicks 额外保留的直线飞行 Tick，负值按零处理
     * @return 目标点位于当前受限转弯路线的可接入区域时返回 {@code true}
     */
    public static boolean canReachTarget(Vec3 position, Vec3 velocity, Vec3 target,
                                         double maxGs, double reserveTicks) {
        return canReachTarget(position, velocity, target, maxGs, 0.5F, reserveTicks);
    }

    /**
     * 评估采用当前弹体转向配置时能否从候选路线接入目标点。
     *
     * @param position 评估起点的世界坐标
     * @param velocity 评估起点的速度，单位格/Tick
     * @param target 固定目标世界坐标
     * @param rvpMaxGs 可选 RVP 最大法向过载，单位 G；非 null 时优先
     * @param turningFactor 未配置 RVP 最大法向过载时使用的方向插值强度
     * @param reserveTicks 额外保留的直线飞行 Tick
     * @return 目标位于当前转向能力的可接入区域时返回 true
     */
    public static boolean canReachTarget(Vec3 position, Vec3 velocity, Vec3 target,
                                         Double rvpMaxGs, float turningFactor,
                                         double reserveTicks) {
        if (!isFinite(position) || !isFinite(velocity) || !isFinite(target)) {
            return false;
        }
        double speed = velocity.length();
        Vec3 targetDelta = target.subtract(position);
        double distance = targetDelta.length();
        if (speed <= 1.0E-8 || distance <= speed) {
            return distance <= speed;
        }

        Vec3 forward = velocity.scale(1.0 / speed);
        double forwardDistance = targetDelta.dot(forward);
        boolean steeringDisabled = rvpMaxGs != null
                ? !Double.isFinite(rvpMaxGs) || rvpMaxGs <= 0.0
                : !Float.isFinite(turningFactor) || turningFactor <= 0.0F;
        if (forwardDistance <= 0.0 || steeringDisabled) {
            return forwardDistance > 0.0
                    && angleBetween(velocity, targetDelta) <= 1.0E-9;
        }

        if (rvpMaxGs != null && rvpMaxGs * PhysicsEngine.G >= 2.0 * speed) {
            return true;
        }
        if (rvpMaxGs == null && turningFactor >= 1.0F) {
            return true;
        }
        double radius = rvpMaxGs != null
                ? speed * speed / (rvpMaxGs * PhysicsEngine.G)
                : resolveTurnRadius(speed, null, turningFactor);
        double lateralDistanceSqr = Math.max(
                targetDelta.lengthSqr() - forwardDistance * forwardDistance, 0.0);
        double lateralDistance = Math.sqrt(lateralDistanceSqr);
        double minimumForwardDistance = lateralDistance >= radius
                ? radius
                : Math.sqrt(Math.max(
                        lateralDistance * (2.0 * radius - lateralDistance), 0.0));
        double reserveDistance = Math.max(reserveTicks, 0.0) * speed;
        return forwardDistance >= minimumForwardDistance + reserveDistance;
    }

    /**
     * 在两个单位方向间做球面插值。
     *
     * @param from 起始单位方向
     * @param to 终止单位方向
     * @param fraction 插值比例，通常位于 0～1
     * @return 归一化后的插值方向
     */
    public static Vec3 slerpDirection(Vec3 from, Vec3 to, double fraction) {
        double dot = Mth.clamp(from.dot(to), -1.0, 1.0);
        if (dot < -0.999999) {
            Vec3 basis = Math.abs(from.x) < 0.9
                    ? new Vec3(1.0, 0.0, 0.0)
                    : new Vec3(0.0, 1.0, 0.0);
            Vec3 axis = from.cross(basis).normalize();
            double angle = Math.PI * fraction;
            return from.scale(Math.cos(angle))
                    .add(axis.cross(from).scale(Math.sin(angle)))
                    .normalize();
        }
        double angle = Math.acos(dot);
        double sinAngle = Math.sin(angle);
        if (sinAngle <= 1.0E-12) {
            return from;
        }
        double fromWeight = Math.sin((1.0 - fraction) * angle) / sinAngle;
        double toWeight = Math.sin(fraction * angle) / sinAngle;
        return from.scale(fromWeight).add(to.scale(toWeight)).normalize();
    }

    /**
     * 把速度长度钳制到配置范围，同时保持方向。
     *
     * @param velocity 输入速度
     * @param minSpeed 最低速率；非正值表示不限制
     * @param maxSpeed 最高速率；非正值表示不限制
     * @return 速率钳制后的速度
     */
    public static Vec3 clampSpeed(Vec3 velocity, float minSpeed, float maxSpeed) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        if (maxSpeed > 0.0f && minSpeed > maxSpeed) {
            minSpeed = 0.0f;
        }
        if (maxSpeed > 0.0f && speed > maxSpeed) {
            return velocity.normalize().scale(maxSpeed);
        }
        if (minSpeed > 0.0f && speed < minSpeed) {
            return velocity.normalize().scale(minSpeed);
        }
        return velocity;
    }

    /**
     * 计算两个方向向量的最小夹角。
     *
     * @param first 第一个方向向量
     * @param second 第二个方向向量
     * @return 夹角，单位弧度；任一向量近似为零时返回零
     */
    public static double angleBetween(Vec3 first, Vec3 second) {
        if (first.lengthSqr() <= 1.0E-12 || second.lengthSqr() <= 1.0E-12) {
            return 0.0;
        }
        return Math.acos(Mth.clamp(
                first.normalize().dot(second.normalize()), -1.0, 1.0));
    }

    /**
     * 由非零速度派生 Minecraft 俯仰角。
     *
     * @param velocity 非零速度向量
     * @return 俯仰角，单位度
     */
    public static float pitchFromVelocity(Vec3 velocity) {
        Vec3 direction = velocity.normalize();
        return (float) Math.toDegrees(-Math.asin(Mth.clamp(direction.y, -1.0, 1.0)));
    }

    /**
     * 由非零速度派生 Minecraft 偏航角。
     *
     * @param velocity 非零速度向量
     * @return 偏航角，单位度
     */
    public static float yawFromVelocity(Vec3 velocity) {
        Vec3 direction = velocity.normalize();
        return (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
    }

    /**
     * 判断向量三个分量是否均为有限数。
     *
     * @param vector 待检查向量；允许为 {@code null}
     * @return 非空且三个分量均非 NaN/Infinity 时返回 {@code true}
     */
    public static boolean isFinite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }
}
