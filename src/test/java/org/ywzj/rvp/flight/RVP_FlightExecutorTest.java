package org.ywzj.rvp.flight;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.flight.RVP_FlightTypes.Command;
import org.ywzj.rvp.flight.RVP_FlightTypes.Switch;
import static org.junit.jupiter.api.Assertions.*;

/** 用实际执行器翻译合同验证本体三轴消费、地面方向与延迟释放。 */
class RVP_FlightExecutorTest {
    /** 可控固定翼端口，不引入Minecraft实体初始化。 */
    static final class TestPort implements RVP_FlightExecutor.Port {
        /** 稳定载具UUID。 */ final UUID id = new UUID(4, 5);
        /** 当前代次。 */ long generation = 7;
        /** 当前物理刻。 */ long tick = 10;
        /** 地面模式。 */ boolean ground;
        /** 写入次数。 */ int writes;
        /** 清理次数。 */ int clears;
        /** 当前俯仰步长，可模拟本体实时响应变化。 */ double pitchStep = 0.2;
        /** 当前偏航步长。 */ double yawStep = 0.5;
        /** 当前滚转步长。 */ double rollStep = 0.2;
        /** 测试端口暂时无法写入。 */ boolean failWrite;
        /** 最后本体输入。 */ RVP_FlightExecutor.Frame frame;
        @Override public UUID vehicleId() { return id; }
        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public long tick() { return tick; }
        @Override public boolean available() { return true; }
        @Override public boolean owns(long value) { return value == generation; }
        @Override public boolean onGround() { return ground; }
        @Override public double pitchStep() { return pitchStep; }
        @Override public double yawStep() { return yawStep; }
        @Override public double rollStep() { return rollStep; }
        @Override public double maximumThrottle() { return 130; }
        @Override public boolean hasLandingGear() { return false; }
        @Override public boolean hasAirbrake() { return false; }
        @Override public void write(RVP_FlightExecutor.Frame value) {
            if (failWrite) throw new IllegalStateException("测试暂时写入失败");
            writes++; frame = value;
        }
        @Override public void clear() { clears++; }
        /** 当前绑定的公共语义指令。 */
        Command command(double pitch, double yaw, double roll) {
            return new Command(id, dimension(), tick, generation, "task", 1, pitch, yaw, roll, 47, Switch.KEEP, Switch.KEEP);
        }
    }

    @Test void airInputsConsumeToRequestedSemanticsIncludingNeutralAndFullRange() {
        for (double value : new double[]{-1, -0.4, 0, 0.4, 1}) {
            TestPort port = new TestPort();
            assertEquals(RVP_FlightExecutor.Result.APPLIED, new RVP_FlightExecutor().execute(port, port.command(value, value, value)));
            assertAll(
                    () -> assertEquals(-value, port.frame.pitchSetter() - port.pitchStep(), 1.0E-9),
                    () -> assertEquals(-value, port.frame.yawSetter() + port.yawStep(), 1.0E-9),
                    () -> assertEquals(value, port.frame.rollSetter() - port.rollStep(), 1.0E-9),
                    () -> assertEquals(47, port.frame.throttle()));
        }
    }

    @Test void groundRightTurnUsesActualRightKeyAndNeutralHasNoFakeLeftTurn() {
        TestPort right = new TestPort(); right.ground = true;
        new RVP_FlightExecutor().execute(right, right.command(0, 0.6, 0.5));
        assertTrue(right.frame.rightYaw()); assertFalse(right.frame.leftYaw());
        assertEquals(-0.6, right.frame.yawSetter() - right.yawStep(), 1.0E-9);
        assertEquals(0, right.frame.rollSetter() - right.rollStep(), 1.0E-9);
        TestPort neutral = new TestPort(); neutral.ground = true;
        new RVP_FlightExecutor().execute(neutral, neutral.command(0, 0, 0));
        assertFalse(neutral.frame.rightYaw()); assertFalse(neutral.frame.leftYaw());
    }

    @Test void duplicateStaleAndDelayedReleaseCannotAffectNewOwner() {
        TestPort port = new TestPort(); RVP_FlightExecutor executor = new RVP_FlightExecutor();
        Command first = port.command(0.2, 0.3, 0.4);
        assertEquals(RVP_FlightExecutor.Result.APPLIED, executor.execute(port, first));
        assertEquals(RVP_FlightExecutor.Result.ALREADY_APPLIED, executor.execute(port, first));
        port.tick++;
        port.pitchStep = 0.4;
        assertEquals(RVP_FlightExecutor.Result.APPLIED_ADJUSTED, executor.execute(port, first));
        assertEquals(-0.2, port.frame.pitchSetter() - port.pitchStep(), 1.0E-9);
        assertEquals(RVP_FlightExecutor.Result.ALREADY_APPLIED, executor.execute(port, first));
        port.generation++;
        assertEquals(RVP_FlightExecutor.Result.NOT_OWNER, executor.execute(port, first));
        assertFalse(executor.release(port, 7));
        assertEquals(0, port.clears);
        assertEquals(RVP_FlightExecutor.Result.APPLIED, executor.execute(port, port.command(0, 0, 0)));
        executor.forget(port.id, 7);
        assertEquals(RVP_FlightExecutor.Result.ALREADY_APPLIED, executor.execute(port, port.command(0, 0, 0)));
        assertTrue(executor.release(port, 8));
        assertEquals(3, port.writes); assertEquals(1, port.clears);
    }

    @Test void unavailableOptionalPartsAreSkippedWhileFlightStillExecutes() {
        TestPort port = new TestPort();
        var command = new Command(port.id, port.dimension(), port.tick, port.generation, "task", 0,
                0.2, 0.3, 0.4, 50, Switch.ON, Switch.OFF);
        var result = new RVP_FlightExecutor().execute(port, command);
        assertEquals(RVP_FlightExecutor.Result.APPLIED_ADJUSTED, result);
        assertTrue(result.applied()); assertTrue(result.accepted());
        assertEquals(1, port.writes);
        assertEquals(Switch.KEEP, port.frame.landingGear()); assertEquals(Switch.KEEP, port.frame.airbrake());
        assertEquals(-0.2, port.frame.pitchSetter() - port.pitchStep(), 1.0E-9);
        assertEquals(-0.3, port.frame.yawSetter() + port.yawStep(), 1.0E-9);
        assertEquals(0.4, port.frame.rollSetter() - port.rollStep(), 1.0E-9);
    }

    @Test void unusualFiniteStepsAndOutOfRangeCommandsAreConsumedButFatalStepsCannotWrite() {
        TestPort port = new TestPort(); port.pitchStep = 2; port.yawStep = -0.5; port.rollStep = 0;
        var executor = new RVP_FlightExecutor();
        var command = new Command(port.id, port.dimension(), 9999, port.generation, "task", 0,
                5, Double.NaN, -10, 9999, null, null);
        assertTrue(executor.execute(port, command).applied());
        assertEquals(-1, port.frame.pitchSetter() - port.pitchStep(), 1.0E-9);
        assertEquals(0, port.frame.yawSetter() + port.yawStep(), 1.0E-9);
        assertEquals(-1, port.frame.rollSetter() - port.rollStep(), 1.0E-9);
        assertEquals(130, port.frame.throttle());
        port.tick++; port.yawStep = Double.NaN;
        assertEquals(RVP_FlightExecutor.Result.INVALID_INPUT, executor.execute(port, command));
        assertEquals(1, port.writes);
    }
}
