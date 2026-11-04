package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.Entry;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.MotorPhase;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 验证实际显示算法的时间和生命周期边界，不创建 Minecraft 或替身弹道。 */
class RVP_VirtualMissileVisualTimelineTest {
    /** 测试维度，不读取世界时间。 */
    private static final ResourceLocation DIMENSION = ResourceLocation.tryParse("minecraft:overworld");
    /** 单枚弹稳定身份，各条时间线独立。 */
    private static final UUID FLIGHT = new UUID(0, 1);

    @Test
    void firstSampleStartsInPlaceThenModelHudAndMultipleCamerasReadSamePose() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(100, 179, 20, MotorPhase.PRIMARY, false), 1000, 50);
        assertEquals(new Vec3(100, 200, 300), timeline.sample(51).position());
        assertTrue(timeline.accept(entry(120, -179, 20, MotorPhase.PRIMARY, false), 1002, 52));
        var halfway = timeline.sample(53);
        assertEquals(110, halfway.position().x, 1.0E-9);
        assertEquals(180, Math.abs(halfway.yaw()), 1.0E-6);
        assertEquals(-30, halfway.pitch(), 1.0E-6, "机头俯仰不能被水平速度抹掉");
        assertEquals(halfway, timeline.sample(53), "多相机、HUD 和模型反复查询不推进游标");
        assertEquals(120, timeline.sample(54).position().x, 1.0E-9);
    }

    @Test
    void latePacketsFreezeExtrapolationStopEffectsThenRemoveModel() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(100, 0, 40, MotorPhase.PRIMARY, false), 1000, 0);
        timeline.accept(entry(120, 0, 40, MotorPhase.PRIMARY, false), 1002, 2);
        var frozen = timeline.sample(7);
        assertEquals(frozen, timeline.sample(12));
        assertTrue(timeline.effectsFresh(12));
        assertFalse(timeline.effectsFresh(13));
        assertFalse(timeline.expired(22));
        assertTrue(timeline.expired(23));
        assertFalse(timeline.canEmitTrail(8));
        assertEquals(MotorPhase.IDLE, timeline.motorPhase(13));
    }

    @Test
    void waitingWithNonzeroVelocityFreezesPositionAgeAndBothMotorCounters() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(120, 70, 0, MotorPhase.SECOND_PULSE, true), 1000, 4);
        assertEquals(timeline.sample(4), timeline.sample(14));
        assertEquals(MotorPhase.SECOND_PULSE, timeline.motorPhase(14), "燃尽边界零刻仍采用权威燃烧阶段");
        assertEquals(0, timeline.motorRemaining(14));
        assertEquals(100, timeline.trailHoldRemaining(14));
        assertFalse(timeline.canEmitTrail(4), "暂停不能在同一点重复排烟");
        assertEquals(MotorPhase.IDLE, timeline.motorPhase(15));
    }

    @Test
    void noClientPredictionCanIgniteSecondPulseOrReuseOldMotorWindow() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(0, 0, 1, MotorPhase.PRIMARY, false), 1, 0);
        assertEquals(MotorPhase.PRIMARY, timeline.motorPhase(1));
        assertEquals(MotorPhase.IDLE, timeline.motorPhase(2));
        timeline.accept(entry(10, 0, 0, MotorPhase.IDLE, false), 3, 2);
        assertEquals(MotorPhase.IDLE, timeline.motorPhase(2));
        assertEquals(100, timeline.trailHoldRemaining(2), "最晚燃尽时间仍有值也不能提前点火");
        timeline.accept(entry(30, 0, 8, MotorPhase.SECOND_PULSE, false), 5, 4);
        assertEquals(MotorPhase.SECOND_PULSE, timeline.motorPhase(4));
        assertEquals(7, timeline.motorRemaining(5));
        assertEquals(99, timeline.trailHoldRemaining(5));
    }

    @Test
    void launchBoostUsesTheCurrentAuthoritativePrimaryWindowAndNeverReplaysOnSecondPulse() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(0, 0, 100, MotorPhase.PRIMARY, false), 1, 0);
        assertEquals(1.5F, timeline.launchBoostFactor(2, 0), 1.0E-6);
        timeline.accept(entry(20, 0, 20, MotorPhase.SECOND_PULSE, false), 3, 2);
        assertEquals(1F, timeline.launchBoostFactor(2, 2), 1.0E-6);
    }

    @Test
    void sameServerTickReplacesSampleOldSampleAndReusedIdCannotRewindIt() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(0, 0, 20, MotorPhase.PRIMARY, false), 1, 0);
        timeline.accept(entry(20, 0, 20, MotorPhase.PRIMARY, false), 3, 2);
        assertTrue(timeline.accept(entry(22, 5, 0, MotorPhase.IDLE, false), 3, 2));
        assertEquals(22, timeline.sample(4).position().x, 1.0E-9);
        assertEquals(MotorPhase.IDLE, timeline.motorPhase(2));
        assertFalse(timeline.accept(entry(-50, 0, 30, MotorPhase.PRIMARY, false), 2, 3));
        var differentUuid = new Entry(UUID.randomUUID(), 1, ResourceLocation.tryParse("rvp:test"),
                Vec3.ZERO, Vec3.ZERO, 0, 0, 1, RVP_VirtualFlightPhase.VIRTUAL_CRUISE, MotorPhase.IDLE, 0, 0);
        assertFalse(timeline.accept(differentUuid, 4, 3));
    }

    @Test
    void restorationUsesActualSafePositionAndOnlyWaitsTenTicksWithoutEffects() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(0, 0, 20, MotorPhase.PRIMARY, false), 1, 0);
        timeline.restore(entry(500, 80, 20, MotorPhase.PRIMARY, true), 2, 2);
        assertEquals(new Vec3(500, 200, 300), timeline.sample(10).position());
        assertFalse(timeline.effectsFresh(2));
        assertFalse(timeline.canEmitTrail(2));
        assertFalse(timeline.expired(11));
        assertTrue(timeline.expired(12));
        assertFalse(timeline.accept(entry(10, 0, 20, MotorPhase.PRIMARY, false), 3, 3));
    }

    @Test
    void suddenPositionChangeBreaksTrailEvenWhenBelowTheGenericSmokeJoinDistance() {
        var timeline = new RVP_VirtualMissileVisualTimeline(entry(0, 0, 20, MotorPhase.PRIMARY, false), 1, 0);
        timeline.accept(entry(20, 0, 20, MotorPhase.PRIMARY, false), 3, 2);
        assertFalse(timeline.breaksTrail());
        timeline.accept(entry(300, 0, 20, MotorPhase.PRIMARY, false), 5, 4);
        assertTrue(timeline.breaksTrail());
        assertEquals(300, timeline.sample(4).position().x, 1.0E-9);
        timeline.accept(entry(320, 0, 20, MotorPhase.PRIMARY, false), 7, 6);
        assertFalse(timeline.breaksTrail());
    }

    @Test
    void receiptGateSeparatesConnectionDimensionSequenceAndServerSampleTime() {
        var gate = new RVP_VirtualMissileVisualTimeline.ReceiptGate();
        UUID session = UUID.randomUUID();
        assertFalse(gate.accept(packet(UUID.randomUUID(), 1, 10), ResourceLocation.tryParse("minecraft:the_nether")));
        assertTrue(gate.accept(packet(session, 1, 10), DIMENSION));
        assertFalse(gate.accept(packet(session, 1, 10), DIMENSION));
        assertTrue(gate.accept(packet(session, 2, 10), DIMENSION), "同刻较新序号必须允许空集合撤销");
        assertFalse(gate.accept(packet(session, 3, 9), DIMENSION));
        assertFalse(gate.accept(packet(UUID.randomUUID(), 100, 100), DIMENSION), "任意不同会话包不能重置绑定");
        // 同一连接重开世界保留最高序号，旧包不能反复污染缓存。
        assertFalse(gate.accept(packet(session, 1, 10), DIMENSION));
        gate.disconnect();
        assertTrue(gate.accept(packet(UUID.randomUUID(), 1, 1), DIMENSION));
    }

    /** 使用实际协议条目构造时间线，水平速度与机头俯仰故意不一致。 */
    private static Entry entry(double x, float yaw, int remaining, MotorPhase motor, boolean paused) {
        return new Entry(FLIGHT, 1, ResourceLocation.tryParse("rvp:test"), new Vec3(x, 200, 300),
                new Vec3(10, 0, 0), yaw, -30, 100,
                paused ? RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS : RVP_VirtualFlightPhase.VIRTUAL_CRUISE,
                motor, remaining, 100);
    }

    /** 空完整集合具有撤销含义，序号门同样必须检查。 */
    private static S2CVirtualMissileVisualSnapshot packet(UUID session, long sequence, long time) {
        return new S2CVirtualMissileVisualSnapshot(DIMENSION, session, sequence, time, List.of(), List.of());
    }
}
