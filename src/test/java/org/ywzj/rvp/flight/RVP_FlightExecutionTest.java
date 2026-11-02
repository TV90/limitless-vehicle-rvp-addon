package org.ywzj.rvp.flight;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.flight.RVP_FlightExecutor.Result;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用生产控制器与执行器验证内部恢复，不靠业务重复提交掩盖中断。 */
class RVP_FlightExecutionTest {
    /** 测试端口的真实本刻姿态，身份必须与执行端一致。 */
    private static Observation observe(RVP_FlightExecutorTest.TestPort port, Terrain terrain) {
        return new Observation(port.id, port.dimension(), port.tick, port.generation, new Vector(0, 180, 0),
                new Vector(0, 0, 35), 0, 0, 0, 0, 60, false, terrain);
    }

    @Test void staleSavedGoalKeepsExecutingForHundredsOfTicksWithoutAnotherSubmission() {
        var port = new RVP_FlightExecutorTest.TestPort(); var leases = new RVP_FlightSessions();
        var lease = leases.acquire(port.id, port.dimension(), java.util.UUID.randomUUID(), () -> true);
        var request = RVP_FixedWingFlightControllerTest.request(new RVP_FlightRequest.HoldCourse(30));
        lease.submit(request, null, 3, port.tick);
        var execution = new RVP_FlightExecution(); var executor = new RVP_FlightExecutor();
        for (int i = 0; i < 200; i++, port.tick++) {
            execution.execute(executor, port, port.generation, (previous, full) -> observe(port, RVP_FixedWingFlightControllerTest.flat()),
                    RVP_FixedWingFlightControllerTest::airframe, lease.request(port.tick), lease.tuning(), !lease.fresh(port.tick));
            assertTrue(execution.result().applied());
            assertEquals(request.goalId(), execution.feedback().goalId());
            assertEquals(request.revision(), execution.feedback().revision());
            assertEquals(30, execution.feedback().headingError(), 1.0E-9);
        }
        assertEquals(200, port.writes); assertTrue(lease.valid());
        assertTrue(execution.feedback().reason().contains("REQUEST_STALE_CONTINUING"));
    }

    @Test void failedTerrainReadFallsBackInSameTickAndCachedAirframePreservesNavigation() {
        var port = new RVP_FlightExecutorTest.TestPort(); var execution = new RVP_FlightExecution();
        var executor = new RVP_FlightExecutor();
        var request = RVP_FixedWingFlightControllerTest.request(new RVP_FlightRequest.HoldCourse(45));
        execution.execute(executor, port, port.generation, (previous, full) -> observe(port, RVP_FixedWingFlightControllerTest.flat()),
                RVP_FixedWingFlightControllerTest::airframe, request, null, false);
        port.tick++; AtomicInteger reads = new AtomicInteger();
        execution.execute(executor, port, port.generation, (previous, full) -> {
            reads.incrementAndGet();
            if (full) throw new IllegalStateException("地形暂时无法读取");
            return observe(port, null);
        }, () -> { throw new IllegalStateException("机体参数暂缺"); }, request, null, false);
        assertEquals(2, reads.get()); assertEquals(2, port.writes);
        assertTrue(execution.result().applied()); assertEquals(45, execution.feedback().headingError(), 1.0E-9);
        assertTrue(execution.feedback().reason().contains("AIRFRAME_ESTIMATED"));
        assertTrue(Double.isFinite(port.frame.pitchSetter())); assertTrue(Double.isFinite(port.frame.throttle()));
    }

    @Test void transientPortFailureAutomaticallyResumesSameGoalOnNextTick() {
        var port = new RVP_FlightExecutorTest.TestPort(); var execution = new RVP_FlightExecution();
        var executor = new RVP_FlightExecutor();
        var request = RVP_FixedWingFlightControllerTest.request(new RVP_FlightRequest.HoldCourse(-30));
        port.failWrite = true;
        execution.execute(executor, port, port.generation, (previous, full) -> observe(port, RVP_FixedWingFlightControllerTest.flat()),
                () -> null, request, null, false);
        assertEquals(Result.CONTINUING, execution.result()); assertTrue(execution.result().accepted());
        assertEquals(0, port.writes); assertEquals(1, port.clears);
        port.failWrite = false; port.tick++;
        execution.execute(executor, port, port.generation, (previous, full) -> observe(port, RVP_FixedWingFlightControllerTest.flat()),
                RVP_FixedWingFlightControllerTest::airframe, request, null, false);
        assertTrue(execution.result().applied()); assertEquals(1, port.writes);
        assertEquals(-30, execution.feedback().headingError(), 1.0E-9);
    }

    @Test void temporaryObservationFailureRetainsGoalButFatalLiveNumbersNeverWrite() {
        var port = new RVP_FlightExecutorTest.TestPort(); var execution = new RVP_FlightExecution();
        var executor = new RVP_FlightExecutor();
        var request = RVP_FixedWingFlightControllerTest.request(new RVP_FlightRequest.HoldCourse(20));
        execution.execute(executor, port, port.generation, (previous, full) -> { throw new IllegalStateException("暂缺实体观察"); },
                () -> null, request, null, false);
        assertEquals(Result.CONTINUING, execution.result()); assertEquals(0, port.writes);
        port.tick++;
        execution.execute(executor, port, port.generation, (previous, full) -> observe(port, RVP_FixedWingFlightControllerTest.flat()),
                RVP_FixedWingFlightControllerTest::airframe, request, null, false);
        assertTrue(execution.result().applied()); assertEquals(1, port.writes);
        port.tick++;
        execution.execute(executor, port, port.generation, (previous, full) ->
                new Observation(port.id, port.dimension(), port.tick, port.generation, new Vector(0, 180, 0),
                        new Vector(0, 0, 35), Double.NaN, 0, 0, 0, 60, false, null),
                RVP_FixedWingFlightControllerTest::airframe, request, null, false);
        assertEquals(Result.INVALID_INPUT, execution.result()); assertFalse(execution.result().accepted());
        assertEquals(1, port.writes);
    }

    @Test void waitingIsAcceptedAndPromotionExecutesSavedRequestAutomatically() {
        var port = new RVP_FlightExecutorTest.TestPort(); var execution = new RVP_FlightExecution();
        var request = RVP_FixedWingFlightControllerTest.request(new RVP_FlightRequest.HoldCourse(60));
        execution.waiting(request, port.tick);
        assertEquals(Result.WAITING_FOR_CONTROL, execution.result()); assertTrue(execution.result().accepted());
        assertEquals(Status.WAITING, execution.feedback().status()); assertEquals(0, port.writes);
        execution.execute(new RVP_FlightExecutor(), port, port.generation,
                (previous, full) -> observe(port, RVP_FixedWingFlightControllerTest.flat()),
                RVP_FixedWingFlightControllerTest::airframe, request, null, false);
        assertTrue(execution.result().applied()); assertEquals(60, execution.feedback().headingError(), 1.0E-9);
    }
}
