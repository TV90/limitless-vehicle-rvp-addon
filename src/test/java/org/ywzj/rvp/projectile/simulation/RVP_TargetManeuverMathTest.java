package org.ywzj.rvp.projectile.simulation;

import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileEntitySimulation;

import static org.junit.jupiter.api.Assertions.*;

/** 用解析几何、独立位置差分和真实目标实体观测核对脚本，避免两个算法共用同一条错误路线。 */
class RVP_TargetManeuverMathTest {
    /** 连续观测和解析轨迹的数值容差。 */
    private static final double EPSILON = 1.0E-8;

    @Test
    void straightAccelerationAndDecelerationHaveTheCorrectPositionAndNoReversal() {
        for (double endSpeed : new double[]{5, 30}) {
            var scene = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(40, endSpeed, 0));
            scene.validate();
            assertEquals(10 + endSpeed, scene.targetAt(40).position()[0], EPSILON);
            assertEquals(endSpeed, scene.targetAt(40).velocity()[0], EPSILON);
            assertEquals(10 + 2 * endSpeed, scene.targetAt(60).position()[0], EPSILON);
            for (int tick = 0; tick <= 80; tick++) assertTrue(scene.targetAt(tick).velocity()[0] >= 0);
        }
    }

    @Test
    void constantSpeedQuarterCircleAndFullCircleMatchIndependentGeometry() {
        var quarter = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(20, 10, 90));
        double radius = 20 / Math.PI;
        assertArrayEquals(new double[]{radius, 164, radius}, quarter.targetAt(20).position(), EPSILON);
        assertArrayEquals(new double[]{0, 0, 10}, quarter.targetAt(20).velocity(), EPSILON);
        var circle = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(80, 10, 360));
        assertArrayEquals(new double[]{0, 164, 0}, circle.targetAt(80).position(), EPSILON);
        assertArrayEquals(new double[]{10, 0, 0}, circle.targetAt(80).velocity(), EPSILON);
    }

    @Test
    void changingSpeedAndHeadingPositionDerivativeMatchesTheReportedVelocity() {
        var scene = scene(20, new RVP_ProjectileSimulationScenario.TargetManeuver(20, 20, 0),
                new RVP_ProjectileSimulationScenario.TargetManeuver(60, 60, -135),
                new RVP_ProjectileSimulationScenario.TargetManeuver(40, 10, 45));
        scene.validate();
        double deltaTick = 1.0E-3;
        for (double tick : new double[]{5, 20, 35, 60, 80, 100, 120, 150}) {
            double[] before = scene.targetAt(tick - deltaTick).position();
            double[] after = scene.targetAt(tick + deltaTick).position();
            double[] velocity = scene.targetAt(tick).velocity();
            for (int axis = 0; axis < 3; axis++) assertEquals(velocity[axis],
                    (after[axis] - before[axis]) * 20 / (2 * deltaTick), 0.002,
                    "位置与速度必须同源，时刻=" + tick + "，坐标轴=" + axis);
        }
    }

    @Test
    void segmentJoinsAreContinuousAndAbsoluteSamplingCanRewind() {
        var scene = scene(35, new RVP_ProjectileSimulationScenario.TargetManeuver(20, 35, 0),
                new RVP_ProjectileSimulationScenario.TargetManeuver(40, 80, 90));
        var first = scene.targetAt(10);
        for (int join : new int[]{20, 60}) {
            var left = scene.targetAt(join - 1.0E-6);
            var right = scene.targetAt(join + 1.0E-6);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(left.position()[axis], right.position()[axis], 1.0E-5);
                assertEquals(left.velocity()[axis], right.velocity()[axis], 1.0E-5);
            }
        }
        scene.targetAt(150);
        assertArrayEquals(first.position(), scene.targetAt(10).position(), 0);
        assertArrayEquals(first.velocity(), scene.targetAt(10).velocity(), 0);
    }

    @Test
    void mirroredManeuversAndSplitSegmentsPreserveTheExactRoute() {
        var right = scene(20, new RVP_ProjectileSimulationScenario.TargetManeuver(80, 60, 120));
        var left = scene(-20, new RVP_ProjectileSimulationScenario.TargetManeuver(80, 60, -120));
        var split = scene(20, new RVP_ProjectileSimulationScenario.TargetManeuver(40, 40, 60),
                new RVP_ProjectileSimulationScenario.TargetManeuver(40, 60, 60));
        for (int tick = 0; tick <= 150; tick++) {
            var normal = right.targetAt(tick); var mirrored = left.targetAt(tick);
            assertEquals(normal.position()[0], -mirrored.position()[0], EPSILON);
            assertEquals(normal.position()[2], mirrored.position()[2], EPSILON);
            assertArrayEquals(normal.position(), split.targetAt(tick).position(), EPSILON);
            assertArrayEquals(normal.velocity(), split.targetAt(tick).velocity(), EPSILON);
        }
    }

    @Test
    void tinyTurnsRemainStableAndZeroEndSpeedDoesNotProduceAnInstantReverse() {
        var straight = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(80, 0, 0));
        var tiny = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(80, 0, 1.0E-9));
        for (int tick = 0; tick <= 150; tick++) {
            assertArrayEquals(straight.targetAt(tick).position(), tiny.targetAt(tick).position(), EPSILON);
            assertTrue(tiny.targetAt(tick).velocity()[0] >= 0);
        }
        assertArrayEquals(new double[]{0, 0, 0}, tiny.targetAt(100).velocity(), EPSILON);
    }

    @Test
    void invalidOrAmbiguousProgramsAreRejectedBeforeAnyEntityIsBuilt() {
        for (double duration : new double[]{0, -1, 1.5, 201, Double.NaN}) {
            var scene = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(duration, 20, 45));
            assertThrows(IllegalArgumentException.class, scene::validate);
        }
        for (double speed : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var scene = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(20, speed, 45));
            assertThrows(IllegalArgumentException.class, scene::validate);
        }
        var missing = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(20, 20, 45), null);
        assertThrows(IllegalArgumentException.class, missing::validate);
        var ambiguous = scene(10, new RVP_ProjectileSimulationScenario.TargetManeuver(20, 20, 45));
        ambiguous.targetAcceleration[0] = 1;
        assertThrows(IllegalArgumentException.class, ambiguous::validate);
        ambiguous.targetAcceleration[0] = 0;
        ambiguous.targetPath = new RVP_ProjectileSimulationScenario.TargetPoint[]{
                new RVP_ProjectileSimulationScenario.TargetPoint(20, new double[]{20, 164, 0})};
        assertThrows(IllegalArgumentException.class, ambiguous::validate);
    }

    @Test
    void actualEntityReceivesTheSameContinuousPositionAndVelocity() {
        var scene = scene(20, new RVP_ProjectileSimulationScenario.TargetManeuver(20, 20, 0),
                new RVP_ProjectileSimulationScenario.TargetManeuver(40, 60, 90));
        scene.target = new double[]{0, 164, 300};
        // 调用既有正式武器解析，不为目标脚本测试复制弹体默认值。
        var weapon = RVP_ProjectileSimulationWeapon.parse("rvp:probe", JsonParser.parseString(
                "{\"type\":\"rvp:missile\",\"guidance_data\":{\"guidance_type\":\"NONE\"}}")
                .getAsJsonObject(), "0".repeat(64));
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        for (int tick = 0; tick <= 100; tick++) {
            if (tick > 0) flight.step();
            var expected = scene.targetAt(tick);
            Vec3 actualPosition = flight.targetPosition();
            assertArrayEquals(expected.position(), new double[]{actualPosition.x, actualPosition.y, actualPosition.z}, EPSILON);
            var observed = flight.entity().getTargetEntity().getDeltaMovement().scale(20);
            assertArrayEquals(expected.velocity(), new double[]{observed.x, observed.y, observed.z}, EPSILON);
        }
    }

    @Test
    void relativeSegmentProjectionFindsAnInteriorEncounter() {
        Vec3 from = new Vec3(-19, 0, 5), to = new Vec3(-20, 0, 0);
        double fraction = RVP_GuidanceEncounterSimulation.closestFraction(from, to);
        assertEquals(6.0 / 26.0, fraction, EPSILON);
        assertEquals(100 / Math.sqrt(26), from.lerp(to, fraction).length(), EPSILON);
    }

    @Test
    void delayedGuidanceCannotBeScoredAsAnEncounterBeforeControlStarts() throws java.io.IOException {
        // 真实 IRIS-T SL 初速较低且延迟点火，旧评分会在第 5 刻误认交会，实际制导最早第 20 刻。
        var weapon = RVP_ProjectileSimulationWeapon.read(java.nio.file.Path.of("limitless_vehicle/rvp/data/rvp/weapons"),
                "rvp:irist_sl");
        var scene = scene(55); scene.weapon = weapon.id(); scene.ticks = 1200;
        scene.target = new double[]{0, 164, 350};
        var result = RVP_GuidanceEncounterSimulation.run(scene, weapon, -1, 0);
        assertTrue(result.firstGuidanceTick() >= 20);
        assertTrue(result.closestTimeTicks() >= result.firstGuidanceTick() - 1);
        assertTrue(result.guidanceAppliedTicks() > 0);
    }

    @Test
    void anUnguidedFlightIsCensoredInsteadOfPretendingToTestGuidance() {
        var weapon = RVP_ProjectileSimulationWeapon.parse("rvp:probe", JsonParser.parseString(
                "{\"type\":\"rvp:missile\",\"guidance_data\":{\"guidance_type\":\"NONE\"}}")
                .getAsJsonObject(), "0".repeat(64));
        var scene = scene(35); scene.target = new double[]{0, 164, 300}; scene.guidance = false;
        var result = RVP_GuidanceEncounterSimulation.run(scene, weapon, -1, 0);
        assertEquals("NO_GUIDANCE", result.termination());
        assertNull(result.firstMissBlocks());
        assertEquals(-1, result.firstGuidanceTick());
    }

    /** 初态只供目标脚本使用；真实导弹测试继续经过生产构造器。 */
    private static RVP_ProjectileSimulationScenario scene(double speed,
            RVP_ProjectileSimulationScenario.TargetManeuver... program) {
        var scene = new RVP_ProjectileSimulationScenario();
        scene.id = "maneuver"; scene.name = "连续机动验证"; scene.weapon = "rvp:probe"; scene.ticks = 200;
        scene.target = new double[]{0, 164, 0}; scene.targetVelocity = new double[]{speed, 0, 0};
        scene.targetManeuvers = program;
        return scene;
    }
}
