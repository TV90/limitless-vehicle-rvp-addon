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
    /** PRESET 当前追点低于弹体时，允许的高度误差死区，单位格。 */
    private static final double PRESET_HEIGHT_OVERRUN_TOLERANCE = 8.0;
    /** PRESET 高度超前补偿从死区外逐渐完全接管的高度范围，单位格。 */
    private static final double PRESET_HEIGHT_COMPENSATION_BLEND_RANGE = 16.0;
    /** 对称抛物线上升支的顶点进度；0 为发射点，1 为目标点。 */
    private static final double PRESET_ASCENDING_APOGEE_PROGRESS = 0.5;

    /** 工具类不允许实例化。 */
    private RVP_BallisticTrajectoryMath() {
    }

    /**
     * 保留既有轨迹数学 API，并委托给共享推进工具计算 RVP 游戏单位下的单 Tick 加速度。
     *
     * @param thrust 发动机推力（RVP 游戏单位）
     * @param mass 弹体质量；推力沿用原值，阻力仅在质量小于 1 时换算为千克
     * @return 推力加速度，单位格/Tick²
     */
    public static double thrustAccelerationPerTick(double thrust, double mass) {
        // 调用本项目共享推进工具：兼容既有调用方，同时由唯一实现维护推力质量换算。
        return RVP_PropulsionMath.accelerationPerTick(thrust, mass);
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
     * @param dragCoefficient 速度平方阻力系数，阻力计算时除以按小质量规则解析后的当前 {@code mass}
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
        if (propulsion && motorTick >= 0 && motorTick <= motorBurnTime) {
            Vec3 direction = velocity.lengthSqr() > 1.0E-8
                    ? velocity.normalize()
                    : new Vec3(0.0, 1.0, 0.0);
            // 调用本项目推进工具：实体与虚拟轨迹共用同一 RVP 推力加速度口径。
            velocity = RVP_PropulsionMath.applyThrust(velocity, direction,
                    new RVP_PropulsionMath.MotorState(true, thrust, mass), 1.0D);
        }

        // 调用本项目统一空气阻力工具：虚拟弹道沿用实体态的质量相关速度平方阻力。
        velocity = RVP_QuadraticAirDrag.apply(
                velocity, dragCoefficient, mass, altitudeDragFactor);
        return gravity != 0.0
                ? velocity.add(0.0, gravity, 0.0)
                : velocity.subtract(0.0, PhysicsEngine.G, 0.0);
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
        RVP_AeroSteeringLimits legacyLimits = new RVP_AeroSteeringLimits(
                maxGs, 0.0F, 0.0, 1.0, 0.0, 0.0, false);
        // 调用本项目统一气动求解入口的关闭分支，保持旧版 G 值转向逐位兼容。
        return applyAeroSteering(velocity, desiredDirection, legacyLimits).velocity();
    }

    /**
     * 旧版常量 G 转向的底层实现，仅供统一气动模型关闭分支调用以避免递归。
     *
     * @param velocity 当前速度向量，单位格/Tick
     * @param desiredDirection 期望飞行方向；允许传入未归一化向量
     * @param maxGs 最大法向过载，单位 G
     * @return 经过旧版 G 值钳制、且保持输入速率的新速度
     */
    static Vec3 applyLegacyMaxGSteering(Vec3 velocity, Vec3 desiredDirection, double maxGs) {
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
     * 按不可变气动限制求解单 Tick 转向，并返回载荷与诱导阻力信息。
     *
     * @param velocity 当前速度向量，单位格/Tick
     * @param desiredDirection 期望飞行方向；允许传入未归一化向量
     * @param limits 当前 Tick 已冻结的气动转向限制
     * @return 统一气动转向求解结果
     */
    public static RVP_AeroSteeringSolution applyAeroSteering(
            Vec3 velocity, Vec3 desiredDirection, RVP_AeroSteeringLimits limits) {
        // 调用本项目纯数学气动模型，使实体链与虚拟链共享完全相同的转角裁决。
        return RVP_AeroSteeringModel.solve(velocity, desiredDirection, limits);
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
        RVP_AeroSteeringLimits legacyLimits = new RVP_AeroSteeringLimits(
                rvpMaxGs, turningFactor, 0.0, 1.0, 0.0, 0.0, false);
        // 调用本项目统一气动求解入口的关闭分支，保留 rvpMaxGs 优先与旧插值语义。
        return applyAeroSteering(velocity, desiredDirection, legacyLimits).velocity();
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
        RVP_AeroSteeringLimits legacyLimits = new RVP_AeroSteeringLimits(
                rvpMaxGs, turningFactor, 0.0, 1.0, 0.0, 0.0, false);
        // 调用本项目气动版 GPS 巡航入口的关闭分支，维持原有公有方法的旧语义。
        return steerGpsCruise(
                position, velocity, target, configuredCruiseAltitude, legacyLimits).velocity();
    }

    /**
     * 为 GPS 巡航生成候选方向，并按当前动压下的气动权限裁决实际转向。
     *
     * @param position 当前世界坐标
     * @param velocity 当前速度，单位格/Tick
     * @param target 固定 GPS 目标世界坐标
     * @param configuredCruiseAltitude 可选世界 Y 巡航高度
     * @param limits 当前 Tick 已冻结的气动转向限制
     * @return 包含巡航转向速度与载荷因子的统一求解结果
     */
    public static RVP_AeroSteeringSolution steerGpsCruise(
            Vec3 position, Vec3 velocity, Vec3 target,
            Double configuredCruiseAltitude, RVP_AeroSteeringLimits limits) {
        // 调用本项目方向生成器与原气动裁决器，保持未启用攻角时的原有求解顺序。
        return applyAeroSteering(velocity, resolveGPSCruiseDirection(
                position, velocity, target, configuredCruiseAltitude, limits), limits);
    }

    /** 生成 GPS 巡航期望方向，不对输出方向施加转角限制，供独立机头姿态求解使用。 */
    public static Vec3 resolveGPSCruiseDirection(
            Vec3 position, Vec3 velocity, Vec3 target,
            Double configuredCruiseAltitude, RVP_AeroSteeringLimits limits) {
        double speed = velocity.length();
        if (speed <= 1.0E-8 || target == null) {
            return velocity;
        }
        Vec3 directTargetDelta = target.subtract(position);
        if (directTargetDelta.length() <= speed * (TERMINAL_RESERVE_TICKS + 1.0)) {
            return directTargetDelta;
        }

        Vec3 horizontalDelta = new Vec3(target.x - position.x, 0.0, target.z - position.z);
        if (horizontalDelta.lengthSqr() <= 1.0E-12) {
            return target.subtract(position);
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
        // 调用本项目统一气动求解器，按动压与角速率上限裁决巡航候选方向。
        RVP_AeroSteeringSolution cruiseSolution = applyAeroSteering(velocity, desired, limits);
        Vec3 cruiseVelocity = cruiseSolution.velocity();
        Vec3 candidatePosition = position.add(cruiseVelocity);
        // 调用本项目气动可达性判断，确保低动压时提前为末端转向保留距离。
        if (canReachTarget(candidatePosition, cruiseVelocity, target,
                limits, TERMINAL_RESERVE_TICKS)) {
            return desired;
        }
        // 不可达时放弃高度目标；实际转向仅由调用方裁决一次。
        return directTargetDelta;
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
        RVP_AeroSteeringLimits legacyLimits = new RVP_AeroSteeringLimits(
                rvpMaxGs, turningFactor, 0.0, 1.0, 0.0, 0.0, false);
        // 调用本项目气动版 PRESET 入口的关闭分支，维持原有公有方法的旧语义。
        return steerPresetBallistic(position, velocity, target, preset, legacyLimits).velocity();
    }

    /**
     * 为 PRESET 弹道生成抛物线中段或终端俯冲方向，并执行气动转向裁决。
     *
     * @param position 当前世界坐标
     * @param velocity 当前速度，单位格/Tick
     * @param target 固定目标世界坐标
     * @param preset PRESET 弹道冻结参数
     * @param limits 当前 Tick 已冻结的气动转向限制
     * @return 包含 PRESET 转向速度与载荷因子的统一求解结果
     */
    public static RVP_AeroSteeringSolution steerPresetBallistic(
            Vec3 position, Vec3 velocity, Vec3 target,
            RVP_BallisticTrajectoryProfile preset, RVP_AeroSteeringLimits limits) {
        // 调用本项目 PRESET 方向生成器与原气动裁决器，保留原公有入口语义。
        return applyAeroSteering(velocity, resolvePresetBallisticDirection(
                position, velocity, target, preset, limits), limits);
    }

    /** 生成 PRESET 中段或末段期望方向，供攻角模型直接使用，避免重复限幅。 */
    public static Vec3 resolvePresetBallisticDirection(
            Vec3 position, Vec3 velocity, Vec3 target,
            RVP_BallisticTrajectoryProfile preset, RVP_AeroSteeringLimits limits) {
        double speed = velocity.length();
        if (speed <= 1.0E-8 || target == null || preset == null
                || preset.launchPosition() == null) {
            return velocity;
        }
        // 调用共用俯冲门与方向生成器，实体和虚拟态不再维护两份过顶逻辑。
        if (shouldBeginPresetDive(position, velocity, target, preset, limits)) {
            return resolvePresetTerminalDirection(position, velocity, target);
        }
        return resolvePresetArcDirection(position, velocity, target, preset, limits);
    }

    /** 三维接近时保留碰撞方向；高空水平过顶继续纠偏，禁止人为加入朝外水平速度。 */
    public static Vec3 resolvePresetTerminalDirection(Vec3 position, Vec3 velocity, Vec3 target) {
        Vec3 delta = target.subtract(position);
        if (delta.lengthSqr() <= 1.0E-12
                || (delta.dot(velocity) < 0.0 && delta.lengthSqr() <= velocity.lengthSqr())) {
            return velocity;
        }
        return delta;
    }

    /** 俯冲提前量包含转弯半径与攻角执行器响应路程，不把理想瞬时满舵当作已达姿态。 */
    public static boolean shouldBeginPresetDive(Vec3 position, Vec3 velocity, Vec3 target,
            RVP_BallisticTrajectoryProfile preset, RVP_AeroSteeringLimits limits) {
        if (target == null || preset == null) return true;
        double speed = velocity.length();
        // 调用共同半径与响应估计，低动压、高速状态提前开始末段修正。
        double turnRadius = resolveTurnRadius(speed, limits);
        double responseDistance = speed * resolvePresetResponseTicks(speed, limits);
        Vec3 launch = preset.launchPosition();
        if (launch != null && limits != null && limits.attackAngleEnabled() && velocity.y > 0.0) {
            // 短射程垂发不能因“水平已很近”立刻向地面压头；先留出完成转向的高度。
            // 高度预算来自实际转弯半径/响应路程，并受配置最高抬升限制。
            double clearance = Math.min(preset.cruiseAltitude(), turnRadius * 2.0 + responseDistance);
            if (position.y < Math.max(launch.y, target.y) + clearance) return false;
        }
        double diveDistance = Math.max(preset.diveRadius(), Math.max(
                Math.max(0.0, position.y - target.y) * preset.diveAltitudeFactor(),
                turnRadius * preset.diveLeadFactor() + responseDistance));
        Vec3 remaining = target.subtract(position).multiply(1, 0, 1);
        if (remaining.lengthSqr() <= diveDistance * diveDistance) return true;
        return launch != null && remaining.dot(target.subtract(launch).multiply(1, 0, 1)) <= 0.0;
    }

    /** 攻角模式预留一阶响应时间及最大惯量松弛时间；关闭攻角时保持原前视距离。 */
    public static double resolvePresetResponseTicks(double speed, RVP_AeroSteeringLimits limits) {
        if (limits == null || !limits.attackAngleEnabled()) return 0.0;
        // 调用真实执行器响应公式；有限上限防止低动压时追点跳过整条弹道。
        double response = RVP_AttackAngleInertiaModel.response(speed, limits);
        return Mth.clamp(1.0 / Math.max(response, 0.05) + 3.0, 3.0, 23.0);
    }

    /** 中段统一追点：前视距离至少覆盖执行器响应路程，末端仍被剩余距离约束。 */
    public static Vec3 resolvePresetArcDirection(Vec3 position, Vec3 velocity, Vec3 target,
            RVP_BallisticTrajectoryProfile preset, RVP_AeroSteeringLimits limits) {
        Vec3 launch = preset.launchPosition();
        Vec3 toTargetHorizontal = new Vec3(
                target.x - position.x, 0.0, target.z - position.z);
        double horizontalDistance = toTargetHorizontal.length();
        if (horizontalDistance <= 1.0E-8) {
            return target.subtract(position);
        }

        Vec3 forwardHorizontal = toTargetHorizontal.normalize();
        double totalHorizontalDistance = horizontalDistance;
        double totalDx = target.x - (launch == null ? position.x : launch.x);
        double totalDz = target.z - (launch == null ? position.z : launch.z);
        double totalDistanceSqr = totalDx * totalDx + totalDz * totalDz;
        if (totalDistanceSqr > 1.0E-8) {
            totalHorizontalDistance = Math.sqrt(totalDistanceSqr);
        }
        double progress = Mth.clamp(
                1.0 - horizontalDistance / totalHorizontalDistance, 0.0, 1.0);
        // 虚拟端与实体端使用同一条“目标高度弦线 + 中段抬升”曲线，避免交接时顶点高度不一致。
        double responseDistance = velocity.length() * resolvePresetResponseTicks(velocity.length(), limits);
        double turnRadius = limits == null ? 0.0 : resolveTurnRadius(velocity.length(), limits);
        // 攻角模式下短射程也给转弯留出高度；仍不超过 JSON 设定的顶点抬升上限。
        double clearance = limits != null && limits.attackAngleEnabled() ? turnRadius * 2.0 + responseDistance : 0.0;
        double apogee = Math.min(preset.cruiseAltitude(), Math.max(totalHorizontalDistance * 0.50, clearance));
        double lookAhead = Math.min(Math.max(preset.maxAscentLead(),
                velocity.length() * resolvePresetResponseTicks(velocity.length(), limits)), horizontalDistance * 0.3);
        // 前视点的水平坐标已经向目标推进，垂直坐标也必须采样同一个前视进度；
        // 不能继续使用当前位置 progress，否则冷发射结束后会追到同高度的水平点。
        PresetLookAhead lookAheadPlan = resolvePresetHeightLookAhead(
                position.y,
                launch == null ? position.y : launch.y,
                target.y,
                progress,
                totalHorizontalDistance,
                lookAhead,
                apogee);
        // 虚拟中段与实体端共用高度超前补偿，保证虚拟化交接后不会重新追低点。
        lookAhead = lookAheadPlan.lookAhead();
        double targetY = lookAheadPlan.targetY();
        Vec3 targetPoint = new Vec3(
                position.x + forwardHorizontal.x * lookAhead,
                targetY,
                position.z + forwardHorizontal.z * lookAhead);

        double maneuverAmplitude = preset.tacticalManeuverAmplitude();
        if (maneuverAmplitude > 0.0) {
            double fadeIn = Mth.clamp((progress - 0.15) / 0.05, 0.0, 1.0);
            double fadeOut = Mth.clamp((0.85 - progress) / 0.05, 0.0, 1.0);
            double weight = fadeIn * fadeOut;
            if (limits != null && limits.attackAngleEnabled()) {
                // 接近末段前先消除蛇形指令，至少预留一段转弯加响应路程，避免横摆惯量带入俯冲。
                double settleDistance = turnRadius * preset.diveLeadFactor() + responseDistance;
                weight *= Mth.clamp((horizontalDistance - 2.0 * settleDistance)
                        / Math.max(settleDistance, 1.0), 0.0, 1.0);
            }
            if (weight > 1.0E-4) {
                double phase = Math.PI * 2.0 * progress * 5.0;
                Vec3 lateral = new Vec3(
                        -forwardHorizontal.z, 0.0, forwardHorizontal.x);
                targetPoint = targetPoint.add(
                        lateral.scale(maneuverAmplitude * Math.sin(phase) * weight));
            }
        }
        return targetPoint.subtract(position);
    }

    /**
     * 计算 PRESET 前视点对应的水平进度。
     *
     * <p>前视点的水平距离为当前位置到目标的剩余距离减去前视量，进度仍以发射点到目标
     * 的总水平距离归一化。实体端和虚拟端必须共用该计算，避免冷发射交接时出现不同的
     * 垂直追踪点。</p>
     *
     * @param horizontalDistance 当前到目标的剩余水平距离，单位格
     * @param totalHorizontalDistance 发射点到目标的总水平距离，单位格
     * @param lookAhead 前视距离，单位格
     * @return 前视点在 PRESET 抛物线上的归一化水平进度
     */
    public static double resolvePresetLookAheadProgress(
            double horizontalDistance, double totalHorizontalDistance, double lookAhead) {
        if (!Double.isFinite(totalHorizontalDistance) || totalHorizontalDistance <= 1.0E-8) {
            return 1.0;
        }
        double safeHorizontalDistance = Math.max(
                0.0, Double.isFinite(horizontalDistance) ? horizontalDistance : totalHorizontalDistance);
        double safeLookAhead = Math.max(0.0, Double.isFinite(lookAhead) ? lookAhead : 0.0);
        double aheadDistance = Math.max(safeHorizontalDistance - safeLookAhead, 0.0);
        return Mth.clamp(1.0 - aheadDistance / totalHorizontalDistance, 0.0, 1.0);
    }

    /**
     * PRESET 高度超前补偿后的前视追点方案。
     *
     * <p>当弹体高度已经超过当前前视追点时，单纯继续追当前点会把期望方向压平，
     * 形成“上升—平飞—再次上升”。本方案在抛物线上升支中反解一个高度略高于当前
     * 弹体的未来点，并保持原有水平前视距离；如果弹体已经高于抛物线顶点，
     * 则保持当前高度，避免追逐不可能达到的低点并产生额外下压。</p>
     *
     * @param positionY 当前弹体世界 Y 坐标，单位格
     * @param launchY 发射点世界 Y 坐标，单位格
     * @param targetY 目标点世界 Y 坐标，单位格
     * @param currentProgress 当前弹体在发射点到目标连线上的水平进度，范围 0～1
     * @param totalHorizontalDistance 发射点到目标点的总水平距离，单位格
     * @param initialLookAhead 当前未补偿的前视距离，单位格
     * @param apogee 抛物线中段相对发射点—目标点弦线的抬升高度，单位格
     * @return 补偿后的前视进度、距离、高度及是否发生补偿
     */
    public static PresetLookAhead resolvePresetHeightLookAhead(
            double positionY,
            double launchY,
            double targetY,
            double currentProgress,
            double totalHorizontalDistance,
            double initialLookAhead,
            double apogee
    ) {
        double safeTotal = Double.isFinite(totalHorizontalDistance)
                ? Math.max(totalHorizontalDistance, 1.0E-8) : 1.0;
        double safeProgress = Mth.clamp(
                Double.isFinite(currentProgress) ? currentProgress : 0.0, 0.0, 1.0);
        double safeLookAhead = Math.max(
                0.0, Double.isFinite(initialLookAhead) ? initialLookAhead : 0.0);
        double initialProgress = Mth.clamp(
                safeProgress + safeLookAhead / safeTotal, 0.0, 1.0);
        double initialTargetY = samplePresetArcHeight(
                launchY, targetY, apogee, initialProgress);

        // 只有上升支且当前高度明显高于追点时才补偿；下降支应交给末端俯冲逻辑。
        if (!Double.isFinite(positionY)
                || !Double.isFinite(launchY)
                || !Double.isFinite(targetY)
                || !Double.isFinite(apogee)
                || apogee <= 0.0
                || safeProgress >= PRESET_ASCENDING_APOGEE_PROGRESS
                || !Double.isFinite(initialTargetY)) {
            return new PresetLookAhead(initialProgress, safeLookAhead, initialTargetY, false);
        }

        double apexY = (launchY + targetY) * 0.5 + apogee;
        // 读取当前位置对应的原始抛物线高度，计算当前前视窗口本来应该产生的爬升量。
        double currentArcY = samplePresetArcHeight(
                launchY, targetY, apogee, safeProgress);
        double nominalRise = Math.max(0.0, initialTargetY - currentArcY);
        double compensatedTargetY;
        if (positionY >= apexY) {
            // 弹体已经高于抛物线顶点时只保持当前高度，不向下追逐不可能达到的低点。
            compensatedTargetY = positionY;
        } else {
            // 保持原水平前视距离，只沿抛物线的正常爬升斜率抬高追踪点，避免
            // “当前高度 + 4 格”配合超长前视距离后把期望爬升角压到接近水平。
            compensatedTargetY = Math.min(apexY, positionY + nominalRise);
        }
        // 用平滑混合替代“超过 8 格就瞬间切换”的硬阈值，避免每 Tick 在两套目标高度之间跳变。
        double overrun = Math.max(0.0, positionY - initialTargetY);
        double blend = Mth.clamp(
                (overrun - PRESET_HEIGHT_OVERRUN_TOLERANCE)
                        / PRESET_HEIGHT_COMPENSATION_BLEND_RANGE,
                0.0, 1.0);
        blend = blend * blend * (3.0 - 2.0 * blend);
        double futureTargetY = Mth.lerp((float) blend, initialTargetY, compensatedTargetY);
        return new PresetLookAhead(
                initialProgress, safeLookAhead, futureTargetY, blend > 1.0E-8);
    }

    /**
     * 高度超前补偿后的 PRESET 前视方案。
     *
     * @param progress 追点在发射点到目标连线上的归一化水平进度，范围 0～1
     * @param lookAhead 追点相对当前弹体的水平前视距离，单位格
     * @param targetY 追点世界 Y 坐标，单位格
     * @param compensated 是否因为当前高度超过原追点而启用了补偿
     */
    public record PresetLookAhead(
            double progress,
            double lookAhead,
            double targetY,
            boolean compensated
    ) {
    }

    /**
     * 按 PRESET 弹道公式采样高度：发射点到目标点的高度弦线，加上中段抬升。
     *
     * @param launchY 发射点世界 Y 坐标，单位格
     * @param targetY 目标点世界 Y 坐标，单位格
     * @param apogee 弹道中段相对发射点—目标点弦线的抬升高度，单位格
     * @param progress 水平进度，0 为发射端、1 为目标端
     * @return 指定进度对应的世界 Y 坐标，单位格
     */
    public static double samplePresetArcHeight(
            double launchY, double targetY, double apogee, double progress) {
        double safeProgress = Mth.clamp(
                Double.isFinite(progress) ? progress : 0.0, 0.0, 1.0);
        double chordY = Mth.lerp((float) safeProgress, launchY, targetY);
        double liftY = apogee * 4.0 * safeProgress * (1.0 - safeProgress);
        return chordY + liftY;
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
     * 按气动模型当前可用 G 值估算最小转弯半径。
     *
     * @param speed 当前速率，单位格/Tick
     * @param limits 当前 Tick 已冻结的气动转向限制
     * @return 钳制在 8～80 格内的近似转弯半径；完全禁止转向时返回正无穷
     */
    public static double resolveTurnRadius(double speed, RVP_AeroSteeringLimits limits) {
        if (limits == null) {
            return Double.POSITIVE_INFINITY;
        }
        if (!limits.enabled()) {
            // 调用本项目旧版转弯半径估算，保证关闭气动开关时路线判定行为不变。
            return resolveTurnRadius(speed, limits.rvpMaxGs(), limits.turningFactor());
        }
        // 调用本项目气动模型，按当前速率对应的归一化动压取得可用过载。
        double currentAvailableGs = RVP_AeroSteeringModel.availableGs(speed, limits);
        if (Double.isInfinite(currentAvailableGs)) {
            return 8.0;
        }
        if (!Double.isFinite(currentAvailableGs) || currentAvailableGs <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double maxDeltaVelocity = currentAvailableGs * PhysicsEngine.G;
        if (maxDeltaVelocity >= 2.0 * speed) {
            return 8.0;
        }
        return Mth.clamp(speed * speed / maxDeltaVelocity, 8.0, 80.0);
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
     * 按当前动压下的气动转向能力评估目标是否仍可接入。
     *
     * @param position 评估起点的世界坐标
     * @param velocity 评估起点的速度，单位格/Tick
     * @param target 固定目标世界坐标
     * @param limits 当前 Tick 已冻结的气动转向限制
     * @param reserveTicks 额外保留的直线飞行 Tick
     * @return 目标位于当前气动转向能力的可接入区域时返回 true
     */
    public static boolean canReachTarget(Vec3 position, Vec3 velocity, Vec3 target,
                                         RVP_AeroSteeringLimits limits,
                                         double reserveTicks) {
        if (limits == null) {
            return false;
        }
        if (!limits.enabled()) {
            // 调用本项目旧版可达性判断，保证阶段 S2 默认关闭时路线决策不发生变化。
            return canReachTarget(position, velocity, target,
                    limits.rvpMaxGs(), limits.turningFactor(), reserveTicks);
        }
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
        // 调用本项目气动模型，取得当前动压下的真实可用过载预算。
        double currentAvailableGs = RVP_AeroSteeringModel.availableGs(speed, limits);
        boolean steeringDisabled = !Double.isFinite(currentAvailableGs)
                ? !Double.isInfinite(currentAvailableGs)
                : currentAvailableGs <= 0.0;
        if (forwardDistance <= 0.0 || steeringDisabled) {
            return forwardDistance > 0.0
                    && angleBetween(velocity, targetDelta) <= 1.0E-9;
        }
        if (Double.isInfinite(currentAvailableGs)
                || currentAvailableGs * PhysicsEngine.G >= 2.0 * speed) {
            return true;
        }

        // 调用本项目气动转弯半径估算，把动压减载同步到路线接入判定。
        double radius = resolveTurnRadius(speed, limits);
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
