package org.ywzj.rvp.vehicle;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 连续实际路径回归，重点检查中间每一步，不能只检查最终目标角。 */
class RVP_OpticalSightMotionTest {
    /** 正式配置约束，速度为度/刻。 */
    private static final RVP_OpticalSightMotion.Limits LIMITS = new RVP_OpticalSightMotion.Limits(-40, 120, -120, 120, 7, 7, 0.1);

    @Test void nadirLateralMoveFirstReorientsWithoutMovingTheImageUp() {
        var motion = create(90, 0);
        Vec3 target = RVP_OpticalSightMath.direction(RVP_OpticalSightMath.rotation(90, 0, 0).rotateY(Math.toRadians(-0.2)));
        for (int tick = 0; tick < 80; tick++) {
            advance(motion, target, LIMITS);
            for (int i = 0; i <= 128; i++) {
                double t = i / 128.0;
                Vec3 direction = RVP_OpticalSightMotion.direction(motion.pitch(t), motion.yaw(t));
                assertEquals(0, direction.z, 1.0E-7, "向右请求不能先向画面上方拐");
                assertTrue(direction.x <= 1.0E-8);
            }
        }
        assertEquals(0, RVP_OpticalSightMotion.direction(motion.pitch(1), motion.yaw(1)).distanceTo(target), 1.0E-6);
    }

    @Test void finiteYawTakesTheAllowedLongRouteInsteadOfGettingStuckAtStop() {
        var motion = create(0, -119);
        double before = -119;
        for (int tick = 0; tick < 100; tick++) {
            advance(motion, RVP_OpticalSightMotion.direction(0, 119), LIMITS);
            assertTrue(motion.yaw(1) >= before - 1.0E-5, "有限轴应经过零度到另一端");
            assertTrue(motion.yaw(1) <= 120 && motion.yaw(1) >= -120);
            assertTrue(Math.abs(motion.yaw(1) - before) <= 7.00001);
            before = motion.yaw(1);
        }
        assertEquals(119, motion.yaw(1), 1.0E-4);
    }

    @Test void samplesDoNotAdvanceStateAndAllAxesRespectSpeedAndLimits() {
        var motion = create(89, 0);
        for (int tick = 0; tick < 100; tick++) {
            double p = motion.pitch(1), y = motion.yaw(1);
            advance(motion, RVP_OpticalSightMotion.direction(95, 80), LIMITS);
            assertTrue(Math.abs(motion.pitch(1) - p) <= 7.0001);
            assertTrue(Math.abs(motion.yaw(1) - y) <= 7.0001);
            var snapshot = motion.snapshot();
            for (int frame = 0; frame <= 240; frame++) {
                double t = frame / 240.0;
                assertTrue(motion.pitch(t) >= -40 && motion.pitch(t) <= 120);
                assertTrue(motion.yaw(t) >= -120 && motion.yaw(t) <= 120);
                assertEquals(motion.view(t), motion.view(t));
            }
            assertArrayEquals(snapshot.pitches(), motion.snapshot().pitches());
        }
    }

    @Test void lostPowerFreezesActualSightEvenIfTheRequestKeepsMoving() {
        var motion = create(40, 12);
        motion.advance(new RVP_OpticalSightMotion.Input(new Quaterniond(), new Quaterniond(),
                RVP_OpticalSightMotion.direction(100, -80), LIMITS, true, false));
        assertEquals(40, motion.pitch(1)); assertEquals(12, motion.yaw(1));
        assertEquals(RVP_OpticalSightMotion.Phase.FROZEN, motion.phase());
    }

    @Test void restoreAndReplayExecutesExactlyTheSameMotion() {
        var original = create(89.5, 0);
        Vec3 target = RVP_OpticalSightMotion.direction(91, 15);
        advance(original, target, LIMITS);
        var snapshot = original.snapshot();
        var replay = create(0, 0); replay.restore(snapshot);
        for (int tick = 0; tick < 30; tick++) {
            advance(original, target, LIMITS); advance(replay, target, LIMITS);
            assertArrayEquals(original.snapshot().pitches(), replay.snapshot().pitches());
            assertArrayEquals(original.snapshot().yaws(), replay.snapshot().yaws());
        }
    }

    private static RVP_OpticalSightMotion create(double pitch, double yaw) {
        var motion = new RVP_OpticalSightMotion();
        // 调用正式初始化，从完整姿态保留画面横轴。
        motion.reset(pitch, yaw, RVP_OpticalSightMath.rotation(pitch, yaw, 0));
        return motion;
    }

    @Test void nearbyNadirLateralSamplesStayOnTheRequestedSightArc() {
        for (double pitch : new double[]{89, 89.9, 90.1, 91}) {
            var motion = create(pitch, 0);
            Vec3 from = RVP_OpticalSightMotion.direction(pitch, 0);
            Vec3 target = RVP_OpticalSightMath.direction(RVP_OpticalSightMath.rotation(pitch, 0, 0).rotateY(Math.toRadians(-0.2)));
            Vec3 planeNormal = from.cross(target).normalize();
            for (int tick = 0; tick < 60; tick++) {
                advance(motion, target, LIMITS);
                for (int i = 0; i <= 128; i++) {
                    Vec3 actual = RVP_OpticalSightMotion.direction(motion.pitch(i / 128.0), motion.yaw(i / 128.0));
                    assertEquals(0, actual.dot(planeNormal), 2.0E-6, "实际画面应留在请求视线弧内");
                }
            }
            assertEquals(0, RVP_OpticalSightMotion.direction(motion.pitch(1), motion.yaw(1)).distanceTo(target), 2.0E-6);
        }
    }

    @Test void damagedAxisImmediatelyHonorsNewSpeedIncludingZero() {
        var motion = create(20, 0);
        Vec3 target = RVP_OpticalSightMotion.direction(50, 80);
        for (int tick = 0; tick < 5; tick++) advance(motion, target, LIMITS);
        var damaged = new RVP_OpticalSightMotion.Limits(-40, 120, -120, 120, 0, 0.01, 0.1);
        double p = motion.pitch(1), y = motion.yaw(1);
        for (int tick = 0; tick < 30; tick++) {
            advance(motion, target, damaged);
            assertEquals(p, motion.pitch(1), 1.0E-12);
            assertTrue(Math.abs(motion.yaw(1) - y) <= 0.010001);
            y = motion.yaw(1);
        }
    }

    @Test void movingAndBankingBaseSharesTheSameMechanicalBudget() {
        var motion = create(60, 0);
        Quaterniond previous = new Quaterniond();
        Vec3 target = RVP_OpticalSightMotion.direction(60, 0);
        for (int tick = 1; tick <= 100; tick++) {
            Quaterniond next = RVP_OpticalSightMath.rotation(Math.sin(tick * 0.1) * 10, tick * 0.3, Math.sin(tick * 0.05) * 25);
            double p = motion.pitch(1), y = motion.yaw(1);
            motion.advance(new RVP_OpticalSightMotion.Input(previous, next, target, LIMITS, true, true));
            assertTrue(Math.abs(motion.pitch(1) - p) <= 7.00001);
            assertTrue(Math.abs(motion.yaw(1) - y) <= 7.00001);
            assertTrue(Double.isFinite(motion.view(0.35).lengthSquared()));
            previous = next;
        }
    }

    @Test void eachSubstepAccelerationIsBoundedDuringTurnAndReversal() {
        var motion = create(20, 0);
        double previousVelocity = 0;
        double accelerationPerStep = 7 / (0.1 * 20) / RVP_OpticalSightMotion.STEPS;
        for (int tick = 0; tick < 60; tick++) {
            advance(motion, RVP_OpticalSightMotion.direction(20, tick < 15 ? 90 : -90), LIMITS);
            var state = motion.snapshot();
            for (int i = 1; i <= RVP_OpticalSightMotion.STEPS; i++) {
                double velocity = (state.yaws()[i] - state.yaws()[i - 1]) * RVP_OpticalSightMotion.STEPS;
                assertTrue(Math.abs(velocity - previousVelocity) <= accelerationPerStep + 1.0E-4);
                previousVelocity = velocity;
            }
        }
    }

    @Test void delayedAcknowledgementReplaysChangingUnconfirmedRequests() {
        var original = create(82, -30);
        var replay = create(82, -30);
        var inputs = new java.util.ArrayList<RVP_OpticalSightMotion.Input>();
        RVP_OpticalSightMotion.Snapshot acknowledged = null;
        for (int tick = 0; tick < 60; tick++) {
            var input = new RVP_OpticalSightMotion.Input(new Quaterniond(), new Quaterniond(),
                    RVP_OpticalSightMotion.direction(86 + Math.sin(tick * 0.2) * 6, tick < 32 ? 45 : -60), LIMITS, true, true);
            inputs.add(input); original.advance(input);
            if (tick == 39) acknowledged = original.snapshot();
        }
        // 收到二十刻前的确认后，只恢复权威初态，再顺序重放此后的不同请求。
        replay.restore(acknowledged);
        for (int tick = 40; tick < inputs.size(); tick++) replay.advance(inputs.get(tick));
        assertArrayEquals(original.snapshot().pitches(), replay.snapshot().pitches());
        assertArrayEquals(original.snapshot().yaws(), replay.snapshot().yaws());
    }
    private static void advance(RVP_OpticalSightMotion motion, Vec3 target, RVP_OpticalSightMotion.Limits limits) {
        // 调用正式执行器，不复制运动公式。
        motion.advance(new RVP_OpticalSightMotion.Input(new Quaterniond(), new Quaterniond(), target, limits, true, true));
    }
}
