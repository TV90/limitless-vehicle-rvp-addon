package org.ywzj.rvp.entity.gunner.flight;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.flight.RVP_FlightRequest;
import org.ywzj.rvp.flight.RVP_FlightTypes.Vector;
import static org.junit.jupiter.api.Assertions.*;

/** 验证驾驶任务与武器目标分离，以及无目标回航和几何脱离。 */
class RVP_GunnerFlightPolicyTest {
    /** 参数化业务观察，不创建游戏实体。 */
    private static RVP_GunnerFlightPolicy.Input input(long tick, Vector position, Vector target, boolean ammo, int mode) {
        return new RVP_GunnerFlightPolicy.Input("gunner", tick, position, 0, 35, position.y() - 64, false,
                new Vector(0, 64, 0), target == null ? -1 : ammo ? 99 : 5, target,
                new Vector(0, 0, 10), !ammo, !ammo, ammo, false,
                0, 1500, 150, 500, 550, 200, 200, mode);
    }

    @Test void missingTargetStillPatrolsAndReturnsUsingHorizontalDistance() {
        var initial = RVP_GunnerFlightPolicy.State.initial();
        var high = RVP_GunnerFlightPolicy.decide(input(1, new Vector(0, 500, 0), null, false, 0), initial);
        assertEquals(RVP_GunnerFlightPolicy.Phase.PATROL, high.state().phase());
        assertInstanceOf(RVP_FlightRequest.Orbit.class, high.request().goal());
        var outside = RVP_GunnerFlightPolicy.decide(input(2, new Vector(600, 300, 0), null, false, 0), high.state());
        assertEquals(RVP_GunnerFlightPolicy.Phase.RETURN, outside.state().phase());
        var edge = RVP_GunnerFlightPolicy.decide(input(3, new Vector(500, 300, 0), null, false, 0), outside.state());
        assertEquals(RVP_GunnerFlightPolicy.Phase.RETURN, edge.state().phase());
    }

    @Test void interceptionDoesNotReplaceExistingNavigationTargetOrAltitude() {
        var approach = RVP_GunnerFlightPolicy.decide(input(1, new Vector(0, 300, 0), new Vector(0, 280, 400), false, 0),
                RVP_GunnerFlightPolicy.State.initial());
        var intercept = RVP_GunnerFlightPolicy.decide(input(2, new Vector(0, 300, 0), new Vector(30, 100, 30), true, 0), approach.state());
        assertEquals(5, intercept.state().missionId());
        assertEquals(approach.request().altitude(), intercept.request().altitude());
        assertEquals(approach.request().revision(), intercept.request().revision());
        assertInstanceOf(RVP_FlightRequest.TrackPoint.class, intercept.request().goal());
    }

    @Test void closeTargetDisengagesAndSeadUsesSameGenericGoals() {
        var close = RVP_GunnerFlightPolicy.decide(input(1, new Vector(0, 300, 0), new Vector(0, 280, 20), false, 0),
                RVP_GunnerFlightPolicy.State.initial());
        assertEquals(RVP_GunnerFlightPolicy.Phase.DISENGAGE, close.state().phase());
        assertFalse(close.attackGeometry());
        var away = RVP_GunnerFlightPolicy.decide(input(2, new Vector(0, 300, 0), new Vector(0, 280, 400), false, 1), close.state());
        assertInstanceOf(RVP_FlightRequest.HoldCourse.class, away.request().goal());
        var toward = RVP_GunnerFlightPolicy.decide(input(3, new Vector(0, 300, 0), new Vector(0, 280, 400), false, 2), away.state());
        assertInstanceOf(RVP_FlightRequest.TrackPoint.class, toward.request().goal());
        assertTrue(toward.request().revision() > away.request().revision());
    }
}
