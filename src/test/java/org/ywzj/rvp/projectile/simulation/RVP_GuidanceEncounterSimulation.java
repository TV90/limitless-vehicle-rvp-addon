package org.ywzj.rvp.projectile.simulation;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileEntitySimulation;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleInertiaModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleState;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;

/** 逐刻运行正式实体，只统计首次接近；不触发近炸，也不让导弹绕圈后的重逢改善评分。 */
final class RVP_GuidanceEncounterSimulation {
    /** 距离回升判定容差，单位格，避免浮点末位噪声被当成首次交会结束。 */
    private static final double RANGE_EPSILON = 1.0E-6;

    /** 工具类不允许实例化。 */
    private RVP_GuidanceEncounterSimulation() {}

    /**
     * @param termination FIRST_ENCOUNTER 为已完成一次接近，其他值是寿终/超时等截断
     * @param firstMissBlocks 已确认首次交会误差；截断时为 null，不能把仍在接近的距离当成脱靶结论
     * @param minimumObservedBlocks 结束前见过的最小距离，截断时只代表当前观测下界
     * @param closestTimeTicks 有效制导后的最近点时刻，Tick；全程无制导时记录全场观测最近点
     * @param steps 真正完成的实体步数
     * @param guidanceAppliedTicks 原控制器实际成功输出的刻数
     * @param firstGuidanceTick 首次有效制导时刻；-1 表示全程没有制导，不对发射前波动评分
     * @param maneuverObserved 最近点发生前是否已经开始脚本机动
     * @param maneuverCompleted 最近点发生前是否已经完成脚本机动
     * @param autonomousCatchTick 按原配置距离提供理想自主捕获事件的刻数；-1 表示没有
     * @param maxNoseTurnDeg 实际最大单刻机头转角，度
     * @param maxNoseBudgetRatio 实际转角/原机头转角预算的最大值；只作诊断
     * @param peakY 首次交会前最高世界 Y，格
     */
    record Score(String termination, Double firstMissBlocks, double minimumObservedBlocks,
                 double closestTimeTicks, int steps, int guidanceAppliedTicks,
                 int firstGuidanceTick,
                 boolean maneuverObserved, boolean maneuverCompleted, int autonomousCatchTick,
                 double maxNoseTurnDeg, double maxNoseBudgetRatio, double peakY) {}

    /** 只替换外部目标观测和捕获事件，飞行阶段、实际机头、推进及阻力全部运行生产代码。 */
    static Score run(RVP_ProjectileSimulationScenario scene, RVP_ProjectileSimulationWeapon weapon,
                     int maneuverStart, int maneuverDuration) {
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        var before = flight.snapshot();
        Vec3 oldTarget = flight.targetPosition();
        double best = before.position().distanceTo(oldTarget), closestTime = 0, peakY = before.position().y;
        double observedMinimum = best, observedClosestTime = 0;
        double maxNoseTurn = 0, maxBudgetRatio = 0;
        int closestSegment = 0, steps = 0, guidedTicks = 0, caughtAt = -1, firstGuidance = -1;
        boolean closingSeen = false;
        String termination = "TIME_LIMIT";
        var type = weapon.data().getGuidanceData().getGuidanceType();
        double activationRange = weapon.data().getGuidanceData().getActiveRadarActivationRange();
        for (int tick = 1; tick <= scene.ticks; tick++) {
            if (caughtAt < 0 && activationRange > 0
                    && (type == RVP_EnumGuidanceType.ARH || type == RVP_EnumGuidanceType.AIR)
                    && before.position().distanceTo(oldTarget) <= activationRange) {
                // 调用正式捕获入口，两算法使用相同开机距离；不声称模拟了雷达搜索或照射。
                flight.entity().markAutonomousSeekerCatch(); caughtAt = tick;
            }
            var physical = weapon.data().getProjectileData();
            Float factor = physical.resolveTurningFactor(tick);
            var limits = physical.resolveAeroSteeringLimits(weapon.data().getProjectileVelocity(),
                    before.position().y, factor == null ? 0.5f : factor);
            // 调用真实实体的一次制导和运动，绝不把理想 PIP/PPN 速度直接写入实体。
            var after = flight.step();
            Vec3 target = flight.targetPosition();
            if (!RVP_BallisticTrajectoryMath.isFinite(after.position())
                    || !RVP_BallisticTrajectoryMath.isFinite(after.velocity())
                    || !Float.isFinite(after.xRot()) || !Float.isFinite(after.yRot())) {
                throw new IllegalStateException(scene.id + " 第 " + tick + " 刻产生非有限实体状态");
            }
            steps = tick;
            if (flight.guidanceAppliedLastTick()) guidedTicks++;
            // 调用方向/预算工具只测量，不改变姿态；保留硬攻角恢复等异常比值供排查。
            Vec3 oldNose = new RVP_AttackAngleState(before.xRot(), before.yRot(), 0, 0).direction();
            Vec3 newNose = new RVP_AttackAngleState(after.xRot(), after.yRot(), 0, 0).direction();
            double turn = Math.toDegrees(RVP_BallisticTrajectoryMath.angleBetween(oldNose, newNose));
            double budget = RVP_AttackAngleInertiaModel.maxTurnDeg(before.velocity().length(), limits);
            maxNoseTurn = Math.max(maxNoseTurn, turn);
            if (budget > 1.0E-9) maxBudgetRatio = Math.max(maxBudgetRatio, turn / budget);
            peakY = Math.max(peakY, after.position().y);
            if (firstGuidance < 0 && flight.guidanceAppliedLastTick()) {
                // 首次实际控制前的低速/点火等待波动不属于制导闭环交会，从本刻起重新建立接近过程。
                firstGuidance = tick;
                best = before.position().distanceTo(oldTarget); closestTime = tick - 1;
                closestSegment = 0;
            }
            Vec3 relative = before.position().subtract(oldTarget);
            Vec3 nextRelative = after.position().subtract(target);
            double fraction = closestFraction(relative, nextRelative);
            double miss = relative.lerp(nextRelative, fraction).length();
            if (miss < observedMinimum) {
                observedMinimum = miss; observedClosestTime = tick - 1 + fraction;
            }
            if (firstGuidance >= 0 && closingSeen && miss > best + RANGE_EPSILON && closestSegment > 1) {
                termination = "FIRST_ENCOUNTER";
                break;
            }
            if (miss < best && firstGuidance >= 0) {
                if (miss < best - RANGE_EPSILON) closingSeen = true;
                best = miss; closestSegment = tick; closestTime = tick - 1 + fraction;
            }
            if (after.position().y <= scene.groundY) { termination = "GROUND_CONTACT"; break; }
            if (after.remainingLife() < 0) { termination = "LIFE_END"; break; }
            if (!flight.entity().isAlive()) { termination = "ENTITY_END"; break; }
            before = after; oldTarget = target;
        }
        if (firstGuidance < 0) { termination = "NO_GUIDANCE"; closestTime = observedClosestTime; }
        boolean confirmed = termination.equals("FIRST_ENCOUNTER");
        return new Score(termination, confirmed ? best : null, observedMinimum, closestTime, steps, guidedTicks, firstGuidance,
                maneuverStart >= 0 && closestTime >= maneuverStart,
                maneuverStart >= 0 && closestTime >= maneuverStart + maneuverDuration,
                caughtAt, maxNoseTurn, maxBudgetRatio, peakY);
    }

    /** 独立相对运动段投影；端点采样不能代替高速交会最短距离。 */
    static double closestFraction(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return delta.lengthSqr() <= 1.0E-16 ? 0
                : Math.max(0, Math.min(1, -from.dot(delta) / delta.lengthSqr()));
    }
}
