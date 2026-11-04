package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 焦点拖动的输入、运动补偿、倍率和帧率回归；无需启动 Minecraft 窗口。 */
class RVP_FocusLockAimTest {
    @Test
    void multipleSmallInputsWithinOneGameTickAccumulateInsteadOfOverwriting() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式锁点状态模拟炮塔尚未移动时的两次细小输入，不能丢掉第一次输入。
        aim.acceptTarget(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1000.0D));
        aim.turn(Vec3.ZERO, 0.1D, 0.0D);
        aim.turn(Vec3.ZERO, 0.1D, 0.0D);

        // 调用方向断言核对总请求为 0.2 度，不是最后一次输入的 0.1 度。
        assertDirection(aim.direction(Vec3.ZERO), direction(0.2D, 0.0D));
    }

    @Test
    void identicalMouseTravelAt30To240FramesProducesIdenticalAim() {
        for (int frames : new int[]{30, 60, 120, 144, 240}) {
            RVP_FocusLockAim aim = new RVP_FocusLockAim();
            // 调用正式状态，所有帧率在一秒内接收相同鼠标总行程。
            aim.acceptTarget(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1024.0D));
            for (int frame = 0; frame < frames; frame++) {
                aim.turn(Vec3.ZERO, 12.0D / frames, 6.0D / frames);
            }

            // 调用方向断言，验证拆分输入的次数不影响最后指向。
            assertDirection(aim.direction(Vec3.ZERO), direction(12.0D, 6.0D));
        }
    }

    @Test
    void movingObserverDoesNotMoveWorldLockOrAddMouseDrift() {
        Vec3 lockedPoint = new Vec3(120.0D, 35.0D, 900.0D);
        for (int frames : new int[]{30, 60, 144, 240}) {
            RVP_FocusLockAim aim = new RVP_FocusLockAim();
            // 调用正式状态模拟载具沿路线移动；无输入时锁点必须始终固定在世界中。
            aim.acceptTarget(Vec3.ZERO, lockedPoint);
            for (int frame = 0; frame <= frames; frame++) {
                double time = (double) frame / frames;
                Vec3 origin = new Vec3(10.0D * time, Math.sin(time) * 2.0D, -5.0D * time);
                aim.turn(origin, 0.0D, 0.0D);
                assertEquals(lockedPoint, aim.target());
                // 调用几何断言，镜头每一帧都应指向同一锁点，而非只在逻辑刻更新。
                assertDirection(aim.direction(origin), lockedPoint.subtract(origin).normalize());
            }
        }
    }

    @Test
    void rapidDirectionReversalLeavesNoQueuedInput() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态，正反输入立即抵消，不额外累积缓慢追赶的鼠标队列。
        aim.acceptTarget(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1000.0D));
        aim.turn(Vec3.ZERO, 25.0D, 10.0D);
        aim.turn(Vec3.ZERO, -25.0D, -10.0D);

        // 调用方向断言核对回到原方向。
        assertDirection(aim.direction(Vec3.ZERO), new Vec3(0.0D, 0.0D, 1.0D));
    }

    @Test
    void skyRayMissKeepsRangeAndRealHitChangesIt() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态，未提供新命中代表选点射线落空，沿用原距离。
        aim.acceptTarget(Vec3.ZERO, new Vec3(0.0D, 0.0D, 200.0D));
        aim.turn(Vec3.ZERO, 15.0D, 4.0D);
        assertEquals(200.0D, aim.target().length(), 1.0E-8D);

        // 调用真实命中接收模拟移到近处墙面，此后微调应保持新的 30 格距离。
        aim.acceptTarget(Vec3.ZERO, aim.direction(Vec3.ZERO).scale(30.0D));
        aim.turn(Vec3.ZERO, 0.2D, 0.1D);
        assertEquals(30.0D, aim.target().length(), 1.0E-8D);
    }

    @Test
    void crossing180DegreesUsesContinuousDirection() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态，模拟瞄准方向跨过南北角度的表示边界。
        aim.acceptTarget(Vec3.ZERO, direction(179.8D, 0.0D).scale(500.0D));
        aim.turn(Vec3.ZERO, 0.4D, 0.0D);

        assertEquals(-179.8D, aim.yaw(), 1.0E-4D);
        // 调用方向断言，边界两侧只转过 0.4 度。
        assertDirection(aim.direction(Vec3.ZERO), direction(-179.8D, 0.0D));
    }

    @Test
    void lookingStraightUpOrDownPreservesYawAndClampsPitch() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态走到正下方，在极点转动方位后重新抬起，方位不能随机跳变。
        aim.acceptTarget(Vec3.ZERO, direction(20.0D, 89.0D).scale(1000.0D));
        aim.turn(Vec3.ZERO, 0.0D, 5.0D);
        aim.turn(Vec3.ZERO, 15.0D, 0.0D);
        aim.turn(Vec3.ZERO, 0.0D, -1.0D);

        // 调用方向断言，限制俯仰但保留玩家在极点输入的方位。
        assertDirection(aim.direction(Vec3.ZERO), direction(35.0D, 89.0D));
    }

    @Test
    void tinyInputsRemainEffectiveNearWorldBorder() {
        Vec3 origin = new Vec3(29_000_000.0D, 200.0D, -29_000_000.0D);
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态验证大世界坐标仍使用双精度，不把细小输入丢进 float 世界坐标。
        aim.acceptTarget(origin, origin.add(0.0D, 0.0D, 1000.0D));
        aim.turn(origin, 0.0001D, 0.0001D);

        assertTrue(aim.target().distanceTo(origin.add(0.0D, 0.0D, 1000.0D)) > 0.001D);
        // 调用方向断言核对微小角度仍然正确。
        assertDirection(aim.direction(origin), direction(0.0001D, 0.0001D));
    }

    @Test
    void invalidOrCoincidentTargetsCannotPoisonExistingLock() {
        Vec3 target = new Vec3(0.0D, 0.0D, 100.0D);
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态，非法目标、重合目标和非法输入均不能覆盖已有有效锁点。
        aim.acceptTarget(Vec3.ZERO, target);
        aim.acceptTarget(Vec3.ZERO, new Vec3(Double.NaN, 0.0D, 0.0D));
        aim.acceptTarget(Vec3.ZERO, Vec3.ZERO);
        aim.turn(Vec3.ZERO, Double.POSITIVE_INFINITY, 1.0D);
        assertEquals(target, aim.target());
        assertNull(aim.direction(new Vec3(0.0D, Double.NaN, 0.0D)));

        // 调用清理模拟换座/换世界，新的会话不允许继承旧锁点。
        aim.clear();
        assertNull(aim.target());
        assertNull(aim.direction(Vec3.ZERO));
    }

    @Test
    void sensitivityFollowsDisplayedZoomIncludingIntermediateTransition() {
        for (double displayedZoom : new double[]{1.0D, 2.0D, 4.0D, 16.0D}) {
            double renderedFov = Math.toDegrees(2.0D * Math.atan(
                    Math.tan(Math.toRadians(70.0D * 0.5D)) / displayedZoom));
            // 调用正式比例解析，配置即使已切到 16 倍也应跟随过渡中的显示倍率。
            assertEquals(1.0D / displayedZoom,
                    RVP_FocusLockAim.sensitivityScale(renderedFov, 70.0D, 16.0D), 1.0E-10D);
        }
        // 调用正式比例解析核对非法视野和扩大视野的回退边界。
        assertEquals(0.25D, RVP_FocusLockAim.sensitivityScale(Double.NaN, 70.0D, 4.0D));
        assertEquals(1.0D, RVP_FocusLockAim.sensitivityScale(90.0D, 70.0D, 4.0D));
        assertEquals(1.0D, RVP_FocusLockAim.sensitivityScale(0.0D, 0.0D, Double.NaN));
    }

    @Test
    void unlockingHandoffHasSameDurationAtDifferentFrameRates() {
        Double reference = null;
        for (int frames : new int[]{30, 60, 144, 240}) {
            double remainingAngle = 20.0D;
            for (int frame = 0; frame < frames; frame++) {
                // 调用正式按秒衰减，以不同帧数经过相同的 0.3 秒。
                remainingAngle *= RVP_FocusLockAim.handoffRetention(0.3D / frames);
            }
            if (reference == null) {
                reference = remainingAngle;
            }
            assertEquals(reference.doubleValue(), remainingAngle, 1.0E-10D);
            assertTrue(remainingAngle < 0.05D);
        }
    }

    /** 根据约定的 Minecraft 方位/俯仰角独立构造预期单位方向，不调用生产瞄准方法。 */
    private static Vec3 direction(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
    }

    /** 按向量距离验证瞄准误差，避免 acos 在极小角度下丢失精度。 */
    private static void assertDirection(Vec3 actual, Vec3 expected) {
        assertNotNull(actual);
        assertEquals(0.0D, actual.distanceTo(expected), 1.0E-9D);
    }
}
