package org.ywzj.rvp.firesupport.server;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 空中支援局部顺延路线的纯数学回归测试。 */
class RVP_FireSupportAirstrikeRoutePlannerTest {
    private static final RVP_FireSupportDeliveryTypes.LocalOffset ZERO_OFFSET =
            new RVP_FireSupportDeliveryTypes.LocalOffset(0.0D, 0.0D, 0.0D);

    @Test
    void reachableScheduleKeepsOriginalReleaseTicksAndExitIsBasedOnLastRelease() {
        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route = plan(List.of(
                target(0, 5, 0.0D), target(1, 7, 4.0D)), 0L, 10.0D, 2.0D);

        assertEquals(5L, route.releases().get(0).actualTick());
        assertEquals(7L, route.releases().get(1).actualTick());
        assertEquals(11L, route.endTick());
        assertEquals(new Vec3(-10.0D, 0.0D, 0.0D), route.positionAt(0L));
    }

    @Test
    void unreachableOriginalIntervalIsLocallyDelayedWithoutEarlyRelease() {
        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route = plan(List.of(
                target(0, 5, 0.0D), target(1, 7, 30.0D), target(2, 8, 34.0D)), 0L, 10.0D, 2.0D);

        assertEquals(5L, route.releases().get(0).actualTick());
        // 向心 Catmull-Rom 的弧长可能略大于控制点直线距离，顺延应以实际平滑曲线为准。
        assertTrue(route.releases().get(1).actualTick() >= 20L);
        assertTrue(route.releases().get(2).actualTick() >= route.releases().get(1).actualTick());
        assertTrue(route.releases().stream().allMatch(release -> release.actualTick() >= release.plannedTick()));
        assertTrue(route.endTick() >= route.releases().get(2).actualTick());
    }

    @Test
    void lateStartRebasesEntryAndKeepsAllOriginalTargets() {
        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route = plan(List.of(
                target(0, 5, 0.0D), target(1, 7, 4.0D)), 50L, 10.0D, 2.0D);

        assertEquals(50L, route.startTick());
        assertEquals(55L, route.releases().get(0).actualTick());
        assertEquals(57L, route.releases().get(1).actualTick());
        assertEquals(new Vec3(-10.0D, 0.0D, 0.0D), route.positionAt(50L));
        assertEquals(new Vec3(0.0D, 0.0D, 0.0D), route.releases().get(0).aircraftCenter());
    }

    @Test
    void rackOffsetIsSubtractedFromReleasePositionForAircraftCenter() {
        RVP_FireSupportDeliveryTypes.LocalOffset rack =
                new RVP_FireSupportDeliveryTypes.LocalOffset(0.0D, -2.0D, 0.0D);
        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route =
                RVP_FireSupportAirstrikeRoutePlanner.plan(
                        List.of(new RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget(
                                0, 5L, new Vec3(0.0D, 10.0D, 0.0D), new Vec3(1.0D, 0.0D, 0.0D))),
                        rack, 10.0D, 8.0D, 2.0D, 0L);

        assertEquals(new Vec3(0.0D, 12.0D, 0.0D), route.releases().get(0).aircraftCenter());
        assertEquals(new Vec3(-10.0D, 12.0D, 0.0D), route.positionAt(0L));
    }

    @Test
    void finalReleaseToExitLegIsStraightAndUsesOutboundDirection() {
        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route =
                RVP_FireSupportAirstrikeRoutePlanner.plan(
                        List.of(
                                targetAt(0, 0L, new Vec3(0.0D, 0.0D, 0.0D)),
                                targetAt(1, 20L, new Vec3(0.0D, 0.0D, 30.0D))),
                        ZERO_OFFSET, 10.0D, 8.0D, 2.0D, 0L);

        assertNotNull(route);
        Vec3 lastCenter = route.releases().get(1).aircraftCenter();
        double lastDistance = route.releases().get(1).distanceAlongRoute();
        assertEquals(new Vec3(8.0D, 0.0D, 30.0D), route.exitPosition());

        route.curve().stream()
                .filter(sample -> sample.distance() > lastDistance + 1.0E-6D)
                .forEach(sample -> {
                    assertEquals(lastCenter.z(), sample.position().z(), 1.0E-6D);
                    assertEquals(1.0D, sample.tangent().dot(route.direction()), 1.0E-6D);
                });
    }

    @Test
    void departureEndTickStartsAtActualFinalDeliveryTick() {
        assertEquals(110L,
                RVP_FireSupportAirstrikeController.departureEndTick(100L, 25.0D, 2.5D));
        assertEquals(133L,
                RVP_FireSupportAirstrikeController.departureEndTick(123L, 25.0D, 2.5D));
    }

    @Test
    void exitRetryPoolRemainsLockedUntilEveryReferencePointWasAttempted() {
        assertFalse(RVP_FireSupportAirstrikeController.allReferencesAttempted(new boolean[] {false}));
        assertFalse(RVP_FireSupportAirstrikeController.allReferencesAttempted(new boolean[] {true, false}));
        assertTrue(RVP_FireSupportAirstrikeController.allReferencesAttempted(new boolean[] {true, true}));
    }

    @Test
    void smoothCurveRemainsFiniteForRepeatedReleasePoints() {
        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route = plan(List.of(
                target(0, 5, 0.0D), target(1, 7, 0.0D), target(2, 9, 30.0D)),
                0L, 10.0D, 2.0D);

        assertNotNull(route);
        assertTrue(route.curve().stream().allMatch(sample -> finite(sample.position())
                && finite(sample.tangent())));
        for (int index = 1; index < route.curve().size(); index++) {
            assertTrue(route.curve().get(index).distance() >= route.curve().get(index - 1).distance());
        }
    }

    @Test
    void mixedGpsAndBallisticReleaseTargetsShareCommonEntryExitAndScheduling() {
        RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget gpsTarget =
                targetAt(0, 5L, new Vec3(0.0D, 100.0D, 0.0D));
        RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget ballisticTarget =
                targetAt(1, 8L, new Vec3(12.0D, 96.0D, 4.0D));

        RVP_FireSupportAirstrikeRoutePlanner.RoutePlan route =
                RVP_FireSupportAirstrikeRoutePlanner.plan(
                        List.of(gpsTarget, ballisticTarget), ZERO_OFFSET,
                        10.0D, 8.0D, 2.0D, 0L);

        assertNotNull(route);
        assertEquals(List.of(0, 1), route.releases().stream()
                .map(RVP_FireSupportAirstrikeRoutePlanner.ScheduledRelease::roundIndex).toList());
        assertEquals(gpsTarget.releasePosition(), route.releases().get(0).releasePosition());
        assertEquals(ballisticTarget.releasePosition(), route.releases().get(1).releasePosition());
        assertEquals(route.releases().get(0).aircraftCenter().subtract(route.direction().scale(10.0D)),
                route.waypoints().get(0).position());
        assertEquals(route.releases().get(1).aircraftCenter().add(route.direction().scale(8.0D)),
                route.exitPosition());
        assertTrue(route.releases().get(0).actualTick() >= gpsTarget.plannedTick());
        assertTrue(route.releases().get(1).actualTick() >= ballisticTarget.plannedTick());
    }

    @Test
    void restrictedTurnRateKeepsForwardMotionContinuous() {
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose pose =
                new RVP_FireSupportAirstrikeRoutePlanner.AircraftPose(
                        Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D), 0.0F, 0.0F, 0.0F,
                        new Vec3(0.0D, 0.0D, 2.0D));
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose next =
                RVP_FireSupportAirstrikeRoutePlanner.advance(pose, new Vec3(0.0D, 0.0D, -1.0D),
                        2.0D, new RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics(0.4D, 2.0D, 3.0D, 8.0D));

        assertTrue(next.position().z() > 0.0D);
        assertTrue(next.forward().dot(pose.forward()) > 0.0D);
        assertEquals(4.0D, next.motion().lengthSqr(), 1.0E-6D);
    }

    @Test
    void multiTickTurnUsesAbsoluteYawOnceAndRollsFromYawDelta() {
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose pose =
                new RVP_FireSupportAirstrikeRoutePlanner.AircraftPose(
                        Vec3.ZERO, new Vec3(-0.5D, 0.0D, 0.8660254D), 0.0F, 30.0F, 0.0F,
                        new Vec3(-0.5D, 0.0D, 0.8660254D).normalize().scale(2.0D));
        RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics dynamics =
                new RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics(0.4D, 2.0D, 3.0D, 8.0D);

        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose next =
                RVP_FireSupportAirstrikeRoutePlanner.advance(
                        pose, new Vec3(0.0D, 0.0D, 1.0D), 2.0D, dynamics);

        // 速度倍率为 0.8，因此偏航最大步长为 2.4 度；绝对角度不能再次被加到当前角度上。
        assertEquals(27.6F, next.yaw(), 1.0E-4F);
        assertEquals(2.4F, Math.abs(next.yaw() - pose.yaw()), 1.0E-4F);
        assertTrue(next.roll() < 0.0F, "左转时内侧左翼应下沉，滚转角应为负");

        for (int tick = 0; tick < 12; tick++) {
            RVP_FireSupportAirstrikeRoutePlanner.AircraftPose following =
                    RVP_FireSupportAirstrikeRoutePlanner.advance(
                            next, new Vec3(0.0D, 0.0D, 1.0D), 2.0D, dynamics);
            assertTrue(Math.abs(following.yaw() - next.yaw()) <= 2.4001F);
            assertTrue(following.forward().dot(next.forward()) > 0.99D);
            next = following;
        }
    }

    @Test
    void rollDirectionIsSymmetricAndStraightFlightStaysLevel() {
        RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics dynamics =
                new RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics(0.4D, 2.0D, 3.0D, 8.0D);
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose leftTurn =
                RVP_FireSupportAirstrikeRoutePlanner.advance(
                        poseWithYaw(30.0F), new Vec3(0.0D, 0.0D, 1.0D), 2.0D, dynamics);
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose rightTurn =
                RVP_FireSupportAirstrikeRoutePlanner.advance(
                        poseWithYaw(-30.0F), new Vec3(0.0D, 0.0D, 1.0D), 2.0D, dynamics);
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose straight =
                RVP_FireSupportAirstrikeRoutePlanner.advance(
                        poseWithYaw(0.0F), new Vec3(0.0D, 0.0D, 1.0D), 2.0D, dynamics);

        assertTrue(leftTurn.roll() < 0.0F, "左转时内侧左翼应下沉");
        assertTrue(rightTurn.roll() > 0.0F, "右转时内侧右翼应下沉");
        assertEquals(Math.abs(leftTurn.roll()), Math.abs(rightTurn.roll()), 1.0E-5F,
                "左右转滚转幅度应保持对称");
        assertEquals(0.0F, straight.roll(), 1.0E-5F, "直线飞行不应产生滚转");
    }

    @Test
    void aircraftLifecycleDoesNotCallADeferredSpawnFailureDestroyed() {
        assertEquals(RVP_FireSupportAirstrikeController.Status.WAITING,
                RVP_FireSupportAirstrikeController.classifyAircraftPresence(
                        false, 100L, 100L, false, false, false));
        assertEquals(RVP_FireSupportAirstrikeController.Status.WAITING,
                RVP_FireSupportAirstrikeController.classifyAircraftPresence(
                        false, 100L, 101L, false, false, false));
        assertEquals(RVP_FireSupportAirstrikeController.Status.AIRCRAFT_SPAWN_FAILED,
                RVP_FireSupportAirstrikeController.classifyAircraftPresence(
                        false, 100L, 102L, false, false, false));
        assertEquals(RVP_FireSupportAirstrikeController.Status.RECOVERING,
                RVP_FireSupportAirstrikeController.classifyAircraftPresence(
                        true, 100L, 102L, false, false, false));
        assertEquals(RVP_FireSupportAirstrikeController.Status.AIRCRAFT_DESTROYED,
                RVP_FireSupportAirstrikeController.classifyAircraftPresence(
                        true, 100L, 102L, false, false, true));
    }

    @Test
    void exactAirstrikeStepUsesTurnedPoseInsteadOfPreviousVelocity() {
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose current =
                new RVP_FireSupportAirstrikeRoutePlanner.AircraftPose(
                        new Vec3(15.5D, 80.0D, 15.5D), new Vec3(0.0D, 0.0D, 1.0D),
                        0.0F, 0.0F, 0.0F, new Vec3(0.0D, 0.0D, 20.0D));
        RVP_FireSupportAirstrikeRoutePlanner.AircraftPose turned =
                RVP_FireSupportAirstrikeRoutePlanner.advance(
                        current, new Vec3(1.0D, 0.0D, 0.0D), 20.0D,
                        RVP_FireSupportAirstrikeRoutePlanner.AircraftDynamics.fallback());

        Vec3 exact = RVP_FireSupportAirstrikeController.exactStepMotion(current, turned);

        assertEquals(turned.position().subtract(current.position()), exact);
        assertTrue(Math.abs(exact.x) > 1.0E-6D, "转弯后的真实下一段必须包含横向位移");
        assertTrue(exact.distanceTo(current.motion()) > 1.0E-6D,
                "硬门禁不得继续使用上一 Tick 的旧速度预测下一段");
    }

    @Test
    void aircraftSpawnsOnlyAfterCallingStageHasEnded() {
        assertTrue(!RVP_FireSupportAirstrikeController.aircraftSpawnAllowed(
                RVP_FireSupportMissionState.CALLING, 100L, 100L),
                "即使到达截止 Tick，CALLING 阶段也不得生成飞机");
        assertTrue(!RVP_FireSupportAirstrikeController.aircraftSpawnAllowed(
                RVP_FireSupportMissionState.STRIKING, 99L, 100L),
                "即使状态已切换，截止 Tick 前也不得生成飞机");
        assertTrue(RVP_FireSupportAirstrikeController.aircraftSpawnAllowed(
                RVP_FireSupportMissionState.STRIKING, 100L, 100L),
                "呼叫结束 Tick 进入 STRIKING 后应允许从出发点生成飞机");
        assertTrue(!RVP_FireSupportAirstrikeController.aircraftSpawnAllowed(
                RVP_FireSupportMissionState.CANCELLED, 101L, 100L),
                "终态任务不得生成飞机");
    }

    @Test
    void recoveryUsesFullTwoHundredTickWindowAndFreezesLogicalTime() {
        assertEquals(RVP_FireSupportAirstrikeController.Status.RECOVERING,
                RVP_FireSupportAirstrikeController.classifyRecovery(false, 100L, 299L));
        assertEquals(RVP_FireSupportAirstrikeController.Status.AIRCRAFT_LOST,
                RVP_FireSupportAirstrikeController.classifyRecovery(false, 100L, 300L));
        assertEquals(RVP_FireSupportAirstrikeController.Status.AIRCRAFT_DESTROYED,
                RVP_FireSupportAirstrikeController.classifyRecovery(true, 100L, 101L));

        assertEquals(19L, RVP_FireSupportAirstrikeController.recoveryScheduleOffset(10L, 100L, 109L));
        assertEquals(10L, RVP_FireSupportAirstrikeController.recoveryScheduleOffset(
                10L, Long.MIN_VALUE, 109L));
    }

    @Test
    void recoveringPathRequestsKeepTargetingTheBlockedLogicalStep() {
        long recoveryStart = 100L;
        long accumulatedPause = 7L;

        long failedMoveStep = RVP_FireSupportAirstrikeController.nextLogicalStepTick(
                true, true, recoveryStart, 99L, accumulatedPause);
        long sameTickPreloadStep = RVP_FireSupportAirstrikeController.nextLogicalStepTick(
                true, false, recoveryStart, recoveryStart, accumulatedPause);
        long nextTickRetryStep = RVP_FireSupportAirstrikeController.nextLogicalStepTick(
                true, true, recoveryStart + 1L, recoveryStart,
                RVP_FireSupportAirstrikeController.recoveryScheduleOffset(
                        accumulatedPause, recoveryStart, recoveryStart + 1L));

        assertEquals(93L, failedMoveStep);
        assertEquals(failedMoveStep, sameTickPreloadStep,
                "同 Tick 末尾的预加载不得覆盖成被阻塞步骤之后的路径");
        assertEquals(failedMoveStep, nextTickRetryStep,
                "下一 Tick 重试必须继续检查同一个冻结逻辑步骤");
    }

    private static RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget target(int roundIndex, long plannedTick,
                                                                               double x) {
        return targetAt(roundIndex, plannedTick, new Vec3(x, 0.0D, 0.0D));
    }

    /** 构造指定释放位置且统一沿 X 轴入场的测试参考弹。 */
    private static RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget targetAt(
            int roundIndex, long plannedTick, Vec3 releasePosition) {
        return new RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget(
                roundIndex, plannedTick, releasePosition, new Vec3(1.0D, 0.0D, 0.0D));
    }

    private static RVP_FireSupportAirstrikeRoutePlanner.RoutePlan plan(
            List<RVP_FireSupportAirstrikeRoutePlanner.ReleaseTarget> targets, long startTick,
            double entryDistance, double speed) {
        return RVP_FireSupportAirstrikeRoutePlanner.plan(
                targets, ZERO_OFFSET, entryDistance, 8.0D, speed, startTick);
    }

    private static RVP_FireSupportAirstrikeRoutePlanner.AircraftPose poseWithYaw(float yaw) {
        double radians = Math.toRadians(-yaw);
        Vec3 forward = new Vec3(Math.sin(radians), 0.0D, Math.cos(radians)).normalize();
        return new RVP_FireSupportAirstrikeRoutePlanner.AircraftPose(
                Vec3.ZERO, forward, 0.0F, yaw, 0.0F, forward.scale(2.0D));
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
