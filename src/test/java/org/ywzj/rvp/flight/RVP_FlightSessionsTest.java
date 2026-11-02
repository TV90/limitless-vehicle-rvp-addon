package org.ywzj.rvp.flight;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 控制会话排他、失效与请求寿命验证。 */
class RVP_FlightSessionsTest {
    @Test void independentBusinessesCannotStealCurrentVehicleAndDelayedCloseIsHarmless() {
        RVP_FlightSessions sessions = new RVP_FlightSessions();
        UUID vehicle = UUID.randomUUID(), gunner = UUID.randomUUID(), uav = UUID.randomUUID();
        AtomicBoolean valid = new AtomicBoolean(true);
        var first = sessions.acquire(vehicle, "world", gunner, valid::get);
        assertNotNull(first);
        var queued = sessions.acquire(vehicle, "world", uav, () -> true);
        assertNotNull(queued); assertTrue(queued.valid()); assertFalse(queued.current());
        assertTrue(first.current());
        valid.set(false);
        var replacement = sessions.acquire(vehicle, "world", uav, () -> true);
        assertSame(queued, replacement); assertTrue(replacement.current());
        assertTrue(replacement.generation() > first.generation());
        first.close();
        assertTrue(replacement.valid()); assertFalse(first.valid());
        assertNotNull(sessions.acquire(vehicle, "another_world", gunner, () -> true));
    }

    @Test void staleRequestsContinueAndOutOfOrderSubmissionsAreAcceptedWithoutOverwritingNewIntent() {
        var sessions = new RVP_FlightSessions();
        var session = sessions.acquire(UUID.randomUUID(), "world", UUID.randomUUID(), () -> true);
        var request = RVP_FixedWingFlightControllerTest.request(new RVP_FlightRequest.HoldCourse(20));
        assertTrue(session.submit(request, RVP_FlightTuning.smoothCruise(), 3, 10));
        assertNull(session.request(9));
        assertTrue(session.fresh(13)); assertFalse(session.fresh(14));
        assertSame(request, session.request(14)); assertSame(request, session.request(1000));
        assertTrue(session.submit(request, RVP_FlightTuning.smoothCruise(), 3, 9));
        var newer = new RVP_FlightRequest(request.goalId(), 2, request.goal(), request.altitude(), request.speed(), request.limits());
        assertTrue(session.submit(newer, RVP_FlightTuning.smoothCruise(), 3, 15));
        assertTrue(session.submit(request, RVP_FlightTuning.smoothCruise(), 3, 16));
        assertSame(newer, session.request(16));
        assertTrue(session.submit(null, null, 500, 20));
        assertSame(newer, session.request(40)); assertTrue(session.fresh(40)); assertFalse(session.fresh(41));
        session.close();
        assertFalse(session.submit(request, RVP_FlightTuning.smoothCruise(), 3, 20));
    }

    @Test void waitingBusinessesRetainGoalsAndPromoteInOrderWithoutAnotherAcquireOrSubmit() {
        var sessions = new RVP_FlightSessions(); UUID vehicle = UUID.randomUUID();
        var first = sessions.acquire(vehicle, "world", UUID.randomUUID(), () -> true);
        AtomicBoolean secondAuthorized = new AtomicBoolean(true);
        var second = sessions.acquire(vehicle, "world", UUID.randomUUID(), secondAuthorized::get);
        var third = sessions.acquire(vehicle, "world", UUID.randomUUID(), () -> true);
        var request = RVP_FixedWingFlightControllerTest.request(new RVP_FlightRequest.HoldCourse(90));
        assertTrue(second.submit(request, null, -10, 1));
        assertTrue(third.submit(request, null, 3, 1));
        assertFalse(second.current()); assertFalse(third.current());
        first.close();
        assertTrue(second.current()); assertFalse(third.current());
        assertSame(request, second.request(100));
        secondAuthorized.set(false); second.close();
        assertTrue(third.current()); assertSame(request, third.request(100));
        first.close(); second.close();
        assertTrue(third.valid()); assertEquals(1, sessions.snapshot().size());
        third.close(); assertFalse(sessions.controlled(vehicle, "world"));
        assertTrue(sessions.snapshot().isEmpty());
    }

    @Test void emptySubmissionStartsSteadyFlightAndBadAuthorizationCannotBreakOtherSessions() {
        var sessions = new RVP_FlightSessions(); UUID vehicle = UUID.randomUUID();
        var first = sessions.acquire(vehicle, "world", UUID.randomUUID(), () -> true);
        assertTrue(first.submit(null, null, 0, 1));
        assertNotNull(first.request(100)); assertNotNull(first.tuning());
        assertInstanceOf(RVP_FlightRequest.Stabilize.class, first.request(100).goal());
        assertNull(sessions.acquire(UUID.randomUUID(), "world", UUID.randomUUID(), () -> { throw new IllegalStateException(); }));
        assertTrue(first.valid());
    }
}
