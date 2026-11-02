package org.ywzj.rvp.flight;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.flight.RVP_FlightRequest.*;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** 公共计算合同验证：业务参数、几何边界和安全恢复不能互相串用。 */
class RVP_FixedWingFlightControllerTest {
    /** 每个用例的稳定载具身份。 */
    private static final UUID VEHICLE = new UUID(1, 2);
    /** 待验证的同一个生产控制器。 */
    private final RVP_FixedWingFlightController controller = new RVP_FixedWingFlightController();

    /** 代表性机体，采用现有本体气动单位。 */
    static Airframe airframe() { return new Airframe(4074, 24444, 16.296, 24.444, 3, 0.85, -10, 25, 1.2, 1.8, 2.4, 130); }
    /** 明确世界高度的公共请求。 */
    static RVP_FlightRequest request(Goal goal) {
        return new RVP_FlightRequest("task", 1, goal, Altitude.worldY(180), Speed.safeCruise(1.25), Limits.cruiseDefaults());
    }
    /** 稳态观察，没有引入世界或实体实例。 */
    static Observation observation(long tick, Vector position, double speed, double yaw, boolean ground, Terrain terrain) {
        double angle = Math.toRadians(yaw);
        return new Observation(VEHICLE, "minecraft:overworld", tick, 7, position,
                new Vector(-Math.sin(angle) * speed, 0, Math.cos(angle) * speed), yaw, 0, 0, 0, 100, ground, terrain);
    }
    /** 当前和前方均为平地。 */
    static Terrain flat() { return new Terrain(64, 64, true, true); }

    @Test void callerParametersChangeAltitudeSpeedAndBankResponse() {
        Observation o = observation(1, new Vector(0, 180, 0), 35, 0, false, flat());
        var low = controller.solve(o, airframe(), request(new HoldCourse(0)), RVP_FlightTuning.smoothCruise(), State.empty());
        var highRequest = new RVP_FlightRequest("task", 1, new HoldCourse(0), Altitude.worldY(220),
                Speed.metresPerSecond(50), Limits.cruiseDefaults().withMaxBankDeg(10));
        var high = controller.solve(o, airframe(), highRequest, RVP_FlightTuning.smoothCruise(), State.empty());
        assertTrue(high.command().pitch() > low.command().pitch(), "更高目标应增加抬头要求");
        assertTrue(high.command().throttlePercent() > low.command().throttlePercent(), "更快目标应增加油门要求");
        var turn = request(new HoldCourse(60));
        var gentleTurn = new RVP_FlightRequest("task", 1, turn.goal(), turn.altitude(), turn.speed(), turn.limits().withMaxBankDeg(10));
        var sharp = controller.solve(o, airframe(), turn, RVP_FlightTuning.smoothCruise(), State.empty());
        var gentle = controller.solve(o, airframe(), gentleTurn, RVP_FlightTuning.smoothCruise(), State.empty());
        assertTrue(Math.abs(gentle.command().roll()) < Math.abs(sharp.command().roll()), "更温和倾斜限制应减少滚转");
    }

    @Test void oppositeHeadingSelectsStableTurnSideAndWrapsAtBoundary() {
        var behind = controller.solve(observation(1, new Vector(0, 180, 0), 35, 0, false, flat()), airframe(),
                request(new HoldCourse(180)), RVP_FlightTuning.smoothCruise(), State.empty());
        assertTrue(behind.command().yaw() > 0 && behind.command().roll() > 0);
        var wrapped = controller.solve(observation(1, new Vector(0, 180, 0), 35, 179, false, flat()), airframe(),
                request(new HoldCourse(-179)), RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(2, wrapped.feedback().headingError(), 1.0E-9);
    }

    @Test void fastWaypointCrossingLatchesExitInsteadOfTurningBack() {
        var r = request(new FlyTo(new Vector(0, 180, 0), 1, 70));
        var before = controller.solve(observation(1, new Vector(-20, 180, 0), 40, -90, false, flat()),
                airframe(), r, RVP_FlightTuning.smoothCruise(), State.empty());
        var after = controller.solve(observation(2, new Vector(20, 180, 0), 40, -90, false, flat()),
                airframe(), r, RVP_FlightTuning.smoothCruise(), before.nextState());
        assertEquals(Status.REACHED, after.feedback().status());
        assertTrue(after.nextState().reached());
        assertEquals(160, after.feedback().headingError(), 1.0E-9);
    }

    @Test void requestedOrbitRadiusIsImmutableAndFeasibleRadiusRespectsBankLimit() {
        var r = request(new Orbit(new Vector(0, 64, 0), 20, 1));
        var result = controller.solve(observation(1, new Vector(20, 180, 0), 35, 0, false, flat()),
                airframe(), r, RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(20, ((Orbit) r.goal()).radius());
        assertTrue(result.feedback().effectiveRadius() > 20);
        assertEquals("RADIUS_EXPANDED", result.feedback().reason());
    }

    @Test void stationaryFollowUsesSpacingOrbitAndRadiusWarningCannotHideUnknownTerrain() {
        var tracking = request(new TrackPoint(new Vector(0, 180, 0), new Vector(0, 0, 0), 1, 0.5, 100));
        var result = controller.solve(observation(1, new Vector(100, 180, 0), 35, 0, false, flat()),
                airframe(), tracking, RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(100, result.feedback().requestedRadius());
        var unknown = controller.solve(observation(1, new Vector(100, 180, 0), 35, 0, false,
                        new Terrain(64, 64, true, false)), airframe(), tracking, RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(Status.LIMITED, unknown.feedback().status());
        assertEquals("TERRAIN_UNKNOWN", unknown.feedback().reason());
    }

    @Test void terrainOverridesInfeasibleHeightAndUnknownTerrainCannotCommandBlindDescent() {
        var r = new RVP_FlightRequest("task", 1, new HoldCourse(0), Altitude.worldY(80), Speed.safeCruise(1.25),
                Limits.cruiseDefaults().withAltitudeLimits(50, 90));
        var conflict = controller.solve(observation(1, new Vector(0, 200, 0), 35, 0, false, flat()), airframe(),
                r, RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals("ALTITUDE_CLEARANCE_CONFLICT", conflict.feedback().reason());
        var unknown = controller.solve(observation(1, new Vector(0, 180, 0), 35, 0, false,
                new Terrain(0, 0, false, false)), airframe(), r, RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(Status.LIMITED, unknown.feedback().status());
        assertTrue(unknown.command().pitch() >= 0);
    }

    @Test void lowSpeedRecoveryWaitsBeforeResumingTaskAndTakeoffWaitsForSpeed() {
        var low = controller.solve(observation(1, new Vector(0, 180, 0), 10, 0, false, flat()), airframe(),
                request(new HoldCourse(90)), RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(Status.RECOVERING, low.feedback().status());
        var recovered = controller.solve(observation(2, new Vector(0, 180, 0), 40, 0, false, flat()), airframe(),
                request(new HoldCourse(90)), RVP_FlightTuning.smoothCruise(), low.nextState());
        assertEquals(Status.RECOVERING, recovered.feedback().status());
        var slowGround = controller.solve(observation(1, new Vector(0, 65, 0), 5, 0, true, flat()), airframe(),
                request(new Takeoff(0)), RVP_FlightTuning.smoothCruise(), State.empty());
        var fastGround = controller.solve(observation(1, new Vector(0, 65, 0), 40, 0, true, flat()), airframe(),
                request(new Takeoff(0)), RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(0, slowGround.command().pitch());
        assertTrue(fastGround.command().pitch() > 0);
    }

    @Test void repairableParametersAreNormalizedAndStillProduceFlightInputs() {
        var unknownTarget = new RVP_FlightRequest("task", 0, new Orbit(new Vector(Double.NaN, 0, 0), 50, 1),
                Altitude.worldY(200), null, null);
        assertInstanceOf(Stabilize.class, unknownTarget.goal());
        assertThrows(IllegalArgumentException.class, () -> observation(1, new Vector(Double.NaN, 0, 0), 35, 0, false, flat()));
        var orbit = new Orbit(new Vector(0, 0, 0), -1, 0);
        assertEquals(1, orbit.radius()); assertEquals(1, orbit.direction());
        assertEquals(1.05, Speed.safeCruise(0.8).value());
        assertEquals(60, Limits.cruiseDefaults().withMaxBankDeg(90).maxBankDeg());
        var repaired = new RVP_FlightRequest("", -9, orbit, null, Speed.safeCruise(Double.NaN), null, null, null);
        var solution = controller.solve(observation(1, new Vector(0, 180, 0), 35, 40, false, flat()),
                airframe(), repaired, null, null);
        assertEquals("anonymous", solution.command().goalId());
        assertEquals(0, solution.command().revision());
        assertEquals(0, solution.feedback().altitudeError());
        assertTrue(Double.isFinite(solution.command().roll()));
        assertTrue(solution.feedback().effectiveRadius() > orbit.radius());
    }

    @Test void aerodynamicAngleIsDistinctFromPitchAndProtectsAgainstHighAoA() {
        var highAoA = new Observation(VEHICLE, "minecraft:overworld", 1, 7, new Vector(0, 180, 0),
                new Vector(0, -25, 35), 0, 10, 0, 0, 100, false, flat());
        var result = controller.solve(highAoA, airframe(), request(new HoldCourse(0)),
                RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(Status.RECOVERING, result.feedback().status());
        assertTrue(result.command().pitch() < 0, "高迎角下降时继续猛抬头不能恢复升力");
    }

    @Test void businessHeightAndSpeedRangesConstrainReferenceAndReportLiftConflict() {
        var limits = Limits.cruiseDefaults().withWorldAltitudeRange(170, 200).withSpeedRange(30, 40);
        var request = new RVP_FlightRequest("task", 1, new HoldCourse(0), Altitude.worldY(100),
                Speed.metresPerSecond(80), limits);
        var result = controller.solve(observation(1, new Vector(0, 180, 0), 35, 0, false, flat()),
                airframe(), request, RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(-10, result.feedback().altitudeError(), 1.0E-9);
        assertEquals(5, result.feedback().speedError(), 1.0E-9);
        var impossible = new RVP_FlightRequest("task", 1, new HoldCourse(0), Altitude.worldY(180),
                Speed.metresPerSecond(20), limits.withSpeedRange(10, 20));
        var conflict = controller.solve(observation(1, new Vector(0, 180, 0), 35, 0, false, flat()),
                airframe(), impossible, RVP_FlightTuning.smoothCruise(), State.empty());
        assertEquals(Status.LIMITED, conflict.feedback().status());
        assertEquals("SPEED_LIFT_CONFLICT", conflict.feedback().reason());
        assertEquals(40, limits.withSpeedRange(50, 40).minimumSpeedMps());
        assertEquals(50, limits.withSpeedRange(50, 40).maximumSpeedMps());
        assertEquals(170, limits.withWorldAltitudeRange(200, 170).minimumWorldY());
        assertEquals(200, limits.withWorldAltitudeRange(200, 170).maximumWorldY());
        assertEquals(100, limits.withAltitudeLimits(20, 100).minimumWorldY());
        assertEquals(170, limits.withAltitudeLimits(20, 100).maximumWorldY());
    }

    @Test void absentGeometryHeightAndHeadingHoldInitialReferencesInsteadOfDriftingOrFlyingToOrigin() {
        var request = new RVP_FlightRequest("hold", 1, new FlyTo(null, 0, Double.NaN), null, null, null);
        var first = controller.solve(observation(1, new Vector(1000, 180, 1000), 35, 40, false, flat()),
                airframe(), request, null, null);
        var drift = controller.solve(observation(2, new Vector(1001, 185, 1001), 35, 45, false, flat()),
                airframe(), request, null, first.nextState());
        assertInstanceOf(Stabilize.class, request.goal());
        assertEquals(-5, drift.feedback().headingError(), 1.0E-9);
        assertEquals(-5, drift.feedback().altitudeError(), 1.0E-9);
        var invalidHeading = controller.solve(observation(1, new Vector(0, 180, 0), 35, 40, false, flat()),
                airframe(), request(new HoldCourse(Double.NaN)), null, null);
        assertEquals(0, invalidHeading.feedback().headingError());
    }

    @Test void extremeFiniteTargetAndIncompleteTuningAndAirframeStillProduceFiniteCommands() {
        var tuning = new RVP_FlightTuning(Double.NaN, -1, Double.POSITIVE_INFINITY, 0, 5, -1,
                0, 100, Double.NaN, -1, 1000, -1, -1, 9999);
        var target = new TrackPoint(new Vector(Double.MAX_VALUE, 180, -Double.MAX_VALUE),
                new Vector(Double.MAX_VALUE, 0, -Double.MAX_VALUE), 1, 100, -100);
        var request = request(target);
        var solution = controller.solve(observation(1, new Vector(0, 180, 0), 35, 0, false, flat()),
                null, request, tuning, null);
        assertTrue(Double.isFinite(solution.command().pitch()));
        assertTrue(Double.isFinite(solution.command().yaw()));
        assertTrue(Double.isFinite(solution.command().roll()));
        assertTrue(Double.isFinite(solution.command().throttlePercent()));
        assertEquals(30000000, ((TrackPoint) request.goal()).point().x());
        assertEquals(2000, ((TrackPoint) request.goal()).velocity().x());
        assertEquals(3, target.predictionSeconds());
        assertEquals(1, tuning.captureTicks()); assertEquals(200, tuning.recoveryHoldTicks());
        var repairedAirframe = new Airframe(Double.NaN, 0, 0, -1, 0, Double.NaN,
                Double.NaN, Double.NaN, 0, 0, 0, Double.NaN);
        var powerless = controller.solve(observation(1, new Vector(0, 180, 0), 35, 0, false, flat()),
                repairedAirframe, request(new HoldCourse(40)), tuning, null);
        assertEquals("AXIS_UNAVAILABLE", powerless.feedback().reason());
        assertTrue(Double.isFinite(powerless.command().throttlePercent()));
    }
}
