package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 从 RVP 实体制导/运动链抽出的纯虚拟弹道实现。
 *
 * <p>制导只生成期望方向，实际方向变化统一交给 {@link #applySteering(Vec3, Vec3, double)}
 * 按 {@code virtual_midcourse_maxg} 限制。实现不读取本体导弹的 maxG，不使用
 * {@code turning_factor} 或每 Tick 最大转角，也不访问世界、实体和区块。</p>
 */
public final class RVP_RvpTrajectoryIntegrator implements RVP_VirtualTrajectoryIntegrator {
    public static final String ID = "rvp_current";
    public static final int VERSION = 4;
    /** 世界 Y 高度误差转换为垂直速度指令的比例增益。比例项（P项） */
    private static final double CRUISE_ALTITUDE_GAIN = 0.015;
    /** 当前垂直速度的阻尼系数，抑制巡航高度附近的振荡。阻尼项（D项） */
    private static final double CRUISE_VERTICAL_DAMPING = 0.05;
    /** 垂直指令相对当前总速率的绝对上限。 */
    private static final double CRUISE_MAX_VERTICAL_COMPONENT = 0.5;
    /** 末端可达性判断额外保留的直线飞行 Tick，吸收离散积分和动力学项误差。 */
    private static final double TERMINAL_RESERVE_TICKS = 2.0;

    @Override public String implementationId() { return ID; }
    @Override public int implementationVersion() { return VERSION; }

    @Override
    public RVP_VirtualTrajectoryResult step(RVP_VirtualTrajectoryState state,
                                             RVP_VirtualGuidanceInput guidance,
                                             RVP_VirtualTrajectoryParameters p) {
        int tick = state.flightTick() + 1;
        Vec3 velocity = state.velocity();

        //先对速度向量施加推力后转动
        if (tick >= p.ignitionTick()) {
            int motorTick = tick - p.ignitionTick();
            if (p.propulsion() && motorTick <= p.motorBurnTime()) {
                //这个推力模型会让弹体沿y方向飞行至推力燃尽，不符合预期。 mc笑传之冲冲爆
                //velocity = velocity.add(directionFromRotation(xRot, yRot).scale(p.thrust() / Math.max(p.mass(), 1.0E-6f)));

                //直接把推力施加在速度向量上
                Vec3 n = velocity.lengthSqr() > 1.0E-8 ? velocity.normalize() : new Vec3(0, 1, 0); // 兜底朝向
                //todo 推力计算可以加入推力曲线，让推力更加平滑,以及发动机按燃烧时间线性减少质量至燃尽
                double acceleration = p.thrust() / Math.max(p.mass(), 1.0E-6f);
                velocity = velocity.add(n.scale(acceleration));
            }

            double speedSqr = velocity.lengthSqr();
            //阻力模型
            double drag = p.dragCoefficient() * p.altitudeDragFactor();
            if (speedSqr > 1.0E-12 && drag > 0.0) {
                velocity = velocity.add(velocity.normalize().scale(-drag * speedSqr));
            }
            //重力影响 todo 模拟现实的 F = Mass * p.gravity() 叠加空气阻力后计算速度
            velocity = p.gravity() != 0f ? velocity.add(0, p.gravity(), 0)
                    : velocity.subtract(0, PhysicsEngine.G, 0);
        }

        Vec3 target = guidance.fixedTargetPosition();
        // steerGpsCruise已实现全程制导闭环
        Vec3 steered = steerGpsCruise(state.position(), velocity, target, p.cruiseAltitude(), p.maxGs(), p.mass());

        double theta = angleBetween(velocity, steered);
        velocity = steered;

        velocity = clampSpeed(velocity, p.minSpeed(), p.maxSpeed());
        // 姿态只由钳制后的权威速度派生，不反向参与本 Tick 的推力或制导计算。
        float xRot = state.xRot();
        float yRot = state.yRot();
        if (velocity.lengthSqr() > 1.0E-8) {
            Vec3 direction = velocity.normalize();
            xRot = (float) Math.toDegrees(-Math.asin(Mth.clamp(direction.y, -1.0, 1.0)));
            yRot = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
        }
        Vec3 position = state.position().add(velocity);
        RVP_VirtualTrajectoryState next = new RVP_VirtualTrajectoryState(
                position, velocity, xRot, yRot, Math.max(state.peakFlightSpeed(), velocity.length()),
                state.flightDistance() + velocity.length(), tick, state.remainingLife() - 1,
                state.secondPulseStartTick());
        return new RVP_VirtualTrajectoryResult(next, theta, !isFinite(next));
    }

    /**
     * 按最大法向过载把当前速度方向转向期望方向，同时严格保持输入速率。
     *
     * <p>单 Tick 允许的速度向量变化量为 {@code maxGs * PhysicsEngine.G}。先把该弦长
     * 换算为最大转角，再在两个单位方向间做球面插值，因此结果同时满足速率不变和
     * G 值上限。零速、非法方向或非正 maxGs 均保持原速度。</p>
     *
     * @param velocity 当前速度向量，单位格/Tick
     * @param desiredDir 期望飞行方向；允许传入未归一化向量
     * @param maxGs 最大法向过载，单位 G
     * @return 经过 G 值钳制的新速度向量，长度与 {@code velocity} 相同
     */
    public static Vec3 applySteering(Vec3 velocity, Vec3 desiredDir, double maxGs) {
        double speed = velocity.length();
        if (!finite(velocity) || desiredDir == null || !finite(desiredDir)
                || speed <= 1.0E-8 || desiredDir.lengthSqr() <= 1.0E-12
                || !Double.isFinite(maxGs) || maxGs <= 0.0) return velocity;
        Vec3 current = velocity.scale(1.0 / speed);
        Vec3 desired = desiredDir.normalize();
        double angle = angleBetween(current, desired);
        if (angle <= 1.0E-12) return velocity;

        double chordRatio = Mth.clamp(maxGs * PhysicsEngine.G / (2.0 * speed), 0.0, 1.0);
        double maxTurn = 2.0 * Math.asin(chordRatio);
        if (maxTurn >= angle) return desired.scale(speed);
        return slerpDirection(current, desired, maxTurn / angle).scale(speed);
    }

    /**
     * 为 GPS 巡航生成“水平指向目标 + 高度闭环”的候选方向，并先检查末端是否可达。
     *
     * <p>规划器先假设本 Tick 继续追踪巡航高度，再用当前速率和 {@code maxGs} 推导最小
     * 转弯半径，检查候选状态能否以受限转弯接入固定目标点。只有候选路线仍可达时才执行
     * 高度指令；否则立即退化为直接追踪目标。因此巡航高度与命中目标冲突时，导弹只会
     * 尽可能接近该高度，不会为了保持高度耗尽末端转弯空间。</p>
     *
     * @param position 当前世界坐标
     * @param velocity 当前速度，单位格/Tick
     * @param target 固定 GPS 目标世界坐标
     * @param configuredCruiseAltitude 可选的世界 Y 巡航高度；为空时以当前高度为闭环基准
     * @param maxGs 最大法向过载，单位 G
     * @return 保持当前速率、方向变化不超过最大 G 值的新速度
     */
    public static Vec3 steerGpsCruise(Vec3 position, Vec3 velocity, Vec3 target,
                                      Double configuredCruiseAltitude, double maxGs, double mass) {
        double speed = velocity.length();
        if (speed <= 1.0E-8) return velocity;
        Vec3 directTargetDelta = target.subtract(position);
        if (directTargetDelta.length() <= speed * (TERMINAL_RESERVE_TICKS + 1.0)) {
            // 已进入近目标区后不再尝试恢复巡航高度，避免最后数 Tick 在两条路线间摆动。
            return applySteering(velocity, directTargetDelta, maxGs);
        }
        Vec3 horizontalDelta = new Vec3(target.x - position.x, 0.0, target.z - position.z);
        if (horizontalDelta.lengthSqr() <= 1.0E-12) {
            // 水平已到达目标时退化为直接追踪三维目标，仍由同一个 G 值钳制器约束。
            return applySteering(velocity, target.subtract(position), maxGs);
        }
        Vec3 horizontalDesired = horizontalDelta.normalize();
        double cruiseAltitude = configuredCruiseAltitude == null ? position.y : configuredCruiseAltitude;
        double altitudeError = cruiseAltitude - position.y;

        double maxVerticalCommand = speed * CRUISE_MAX_VERTICAL_COMPONENT;
        /*PD 控制器
          比例项（P项）：:altitudeError * CRUISE_ALTITUDE_GAIN ;
          微分项/阻尼项（D项）- velocity.y * CRUISE_VERTICAL_DAMPING */
        double verticalComponent =
//                PhysicsEngine.G / mass +
                altitudeError * CRUISE_ALTITUDE_GAIN - velocity.y * CRUISE_VERTICAL_DAMPING;
        double verticalCommand = Mth.clamp(
                verticalComponent,
                -maxVerticalCommand, maxVerticalCommand);
        //期望速度方向
        Vec3 desired = new Vec3(horizontalDesired.x, verticalCommand / speed, horizontalDesired.z).normalize();
        //进行g钳制
        Vec3 cruiseVelocity = applySteering(velocity, desired, maxGs);
        Vec3 candidatePosition = position.add(cruiseVelocity);
        //判断当前g钳制下目标点是否可达
        if (canReachTarget(candidatePosition, cruiseVelocity, target, maxGs, TERMINAL_RESERVE_TICKS)) {
            return cruiseVelocity;
        }
        // 高度路线会侵占末端转弯空间时，命中目标的优先级高于巡航高度。
        return applySteering(velocity, directTargetDelta, maxGs);
    }

    /**
     * 评估当前姿态能否在最大 G 值钳制下、不绕回目标后方地接入目标点。
     *
     * <p>把当前位置、速度方向和目标点张成的平面视为二维转弯平面。由速度弦长约束得到
     * 最小转弯半径 {@code radius = speed^2 / (maxGs * G)}。目标横向偏移不超过半径时，
     * 最短接入路线是圆弧接切线；偏移更大时，先转到横向方向再直飞。由此求出所需的最小
     * 前向距离，并附加少量 Tick 的安全余量。该判断只读取数学状态，不访问世界。</p>
     *
     * @param position 评估起点的世界坐标
     * @param velocity 评估起点的速度，单位格/Tick
     * @param target 固定目标世界坐标
     * @param maxGs 最大法向过载，单位 G
     * @param reserveTicks 额外保留的直线飞行 Tick，必须为非负数
     * @return 目标点位于当前受限转弯路线的可接入区域时返回 true
     */
    static boolean canReachTarget(Vec3 position, Vec3 velocity, Vec3 target,
                                  double maxGs, double reserveTicks) {
        if (!finite(position) || !finite(velocity) || !finite(target)) return false;
        double speed = velocity.length();
        Vec3 targetDelta = target.subtract(position);
        double distance = targetDelta.length();
        if (speed <= 1.0E-8 || distance <= speed) return distance <= speed;

        Vec3 forward = velocity.scale(1.0 / speed);
        double forwardDistance = targetDelta.dot(forward);
        if (forwardDistance <= 0.0 || !Double.isFinite(maxGs) || maxGs <= 0.0) {
            return forwardDistance > 0.0 && angleBetween(velocity, targetDelta) <= 1.0E-9;
        }

        double maxDeltaV = maxGs * PhysicsEngine.G;
        if (maxDeltaV >= 2.0 * speed) return true;
        double radius = speed * speed / maxDeltaV;
        double lateralDistanceSqr = Math.max(targetDelta.lengthSqr()
                - forwardDistance * forwardDistance, 0.0);
        double lateralDistance = Math.sqrt(lateralDistanceSqr);
        double minimumForwardDistance = lateralDistance >= radius
                ? radius
                : Math.sqrt(Math.max(lateralDistance * (2.0 * radius - lateralDistance), 0.0));
        double reserveDistance = Math.max(reserveTicks, 0.0) * speed;
        return forwardDistance >= minimumForwardDistance + reserveDistance;
    }

    /** 在两个单位向量间做球面插值；反向向量使用确定性的正交轴避免数值奇点。 */
    private static Vec3 slerpDirection(Vec3 from, Vec3 to, double fraction) {
        double dot = Mth.clamp(from.dot(to), -1.0, 1.0);
        if (dot < -0.999999) {
            Vec3 basis = Math.abs(from.x) < 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 axis = from.cross(basis).normalize();
            double angle = Math.PI * fraction;
            return from.scale(Math.cos(angle)).add(axis.cross(from).scale(Math.sin(angle))).normalize();
        }
        double angle = Math.acos(dot);
        double sinAngle = Math.sin(angle);
        if (sinAngle <= 1.0E-12) return from;
        double fromWeight = Math.sin((1.0 - fraction) * angle) / sinAngle;
        double toWeight = Math.sin(fraction * angle) / sinAngle;
        return from.scale(fromWeight).add(to.scale(toWeight)).normalize();
    }

    private static Vec3 clampSpeed(Vec3 velocity, float min, float max) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) return velocity;
        if (max > 0f && min > max) min = 0f;
        if (max > 0f && speed > max) return velocity.normalize().scale(max);
        if (min > 0f && speed < min) return velocity.normalize().scale(min);
        return velocity;
    }
    private static double horizontalLength(Vec3 v) { return Math.sqrt(v.x * v.x + v.z * v.z); }
    private static Vec3 directionFromRotation(float pitch, float yaw) {
        float y = -yaw * ((float)Math.PI / 180f) - (float)Math.PI;
        float x = -pitch * ((float)Math.PI / 180f);
        return new Vec3(Mth.sin(y) * Mth.cos(x), Mth.sin(x), Mth.cos(y) * Mth.cos(x));
    }
    private static double angleBetween(Vec3 a, Vec3 b) {
        if (a.lengthSqr() <= 1.0E-12 || b.lengthSqr() <= 1.0E-12) return 0.0;
        return Math.acos(Mth.clamp(a.normalize().dot(b.normalize()), -1.0, 1.0));
    }
    private static boolean isFinite(RVP_VirtualTrajectoryState s) {
        return finite(s.position()) && finite(s.velocity()) && Float.isFinite(s.xRot()) && Float.isFinite(s.yRot())
                && Double.isFinite(s.peakFlightSpeed()) && Double.isFinite(s.flightDistance());
    }
    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }

}
