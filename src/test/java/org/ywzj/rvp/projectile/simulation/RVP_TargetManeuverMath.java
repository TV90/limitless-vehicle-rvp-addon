package org.ywzj.rvp.projectile.simulation;

/** 只生成目标的外部连续观测；段内水平速率、航向线性变化，位置由同一速度函数精确积分。 */
final class RVP_TargetManeuverMath {
    /** Minecraft 固定每秒刻数；目标配置速度使用格/秒。 */
    private static final double TICKS_PER_SECOND = 20.0;
    /** 小转角用级数计算，避免两个接近的三角函数值相减丢失精度。 */
    private static final double SMALL_ANGLE = 1.0E-3;

    /** 工具类不允许实例化。 */
    private RVP_TargetManeuverMath() {}

    /** 绝对时刻取样无历史依赖，重复取样、倒拖与两个算法进程获得完全相同的目标轨迹。 */
    static RVP_ProjectileSimulationScenario.TargetState sample(double[] origin, double[] initialVelocity,
            RVP_ProjectileSimulationScenario.TargetManeuver[] program, double tick) {
        if (!Double.isFinite(tick) || tick < 0) throw new IllegalArgumentException("机动取样时间必须为非负有限 Tick");
        double[] position = origin.clone();
        double heading = Math.atan2(initialVelocity[2], initialVelocity[0]);
        double speed = Math.hypot(initialVelocity[0], initialVelocity[2]);
        double elapsedTicks = 0;
        for (var segment : program) {
            double seconds = Math.min(tick - elapsedTicks, segment.durationTicks()) / TICKS_PER_SECOND;
            double duration = segment.durationTicks() / TICKS_PER_SECOND;
            double acceleration = (segment.endSpeed() - speed) / duration;
            double angularRate = Math.toRadians(segment.turnDeg()) / duration;
            // 调用解析位移函数，保证位置导数与交给生产制导的速度属于同一条连续路线。
            double[] displacement = displacement(speed, acceleration, heading, angularRate, seconds);
            position[0] += displacement[0]; position[2] += displacement[1];
            if (tick < elapsedTicks + segment.durationTicks()) {
                double currentSpeed = speed + acceleration * seconds;
                double currentHeading = heading + angularRate * seconds;
                position[1] = origin[1] + initialVelocity[1] * tick / TICKS_PER_SECOND;
                return new RVP_ProjectileSimulationScenario.TargetState(position,
                        new double[]{currentSpeed * Math.cos(currentHeading), initialVelocity[1],
                                currentSpeed * Math.sin(currentHeading)});
            }
            speed = segment.endSpeed(); heading += Math.toRadians(segment.turnDeg());
            elapsedTicks += segment.durationTicks();
        }
        double coastSeconds = (tick - elapsedTicks) / TICKS_PER_SECOND;
        double vx = speed * Math.cos(heading), vz = speed * Math.sin(heading);
        position[0] += vx * coastSeconds; position[2] += vz * coastSeconds;
        position[1] = origin[1] + initialVelocity[1] * tick / TICKS_PER_SECOND;
        return new RVP_ProjectileSimulationScenario.TargetState(position, new double[]{vx, initialVelocity[1], vz});
    }

    /** 对 (speed+a*t)×[cos(heading+w*t), sin(heading+w*t)] 积分，避免按每刻折线伪装平滑变向。 */
    private static double[] displacement(double speed, double acceleration, double heading,
                                          double angularRate, double seconds) {
        double halfAngle = angularRate * seconds * 0.5;
        double squared = halfAngle * halfAngle;
        double sinc = Math.abs(halfAngle) < SMALL_ANGLE
                ? 1 - squared / 6 + squared * squared / 120 - squared * squared * squared / 5040
                : Math.sin(halfAngle) / halfAngle;
        double odd = Math.abs(halfAngle) < SMALL_ANGLE
                ? halfAngle * (1.0 / 3 - squared / 30 + squared * squared / 840)
                : (Math.sin(halfAngle) - halfAngle * Math.cos(halfAngle)) / squared;
        double along = (speed + acceleration * seconds * 0.5) * seconds * sinc;
        double normal = acceleration * seconds * seconds * 0.5 * odd;
        double middleHeading = heading + halfAngle;
        return new double[]{along * Math.cos(middleHeading) - normal * Math.sin(middleHeading),
                along * Math.sin(middleHeading) + normal * Math.cos(middleHeading)};
    }
}
