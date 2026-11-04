package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void finitePointTrimKeepsRangeUntilActualRayResultChangesIt() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态，单独累加鼠标只计算候选方向；真实命中语义由 acceptRay 接收。
        aim.acceptTarget(Vec3.ZERO, new Vec3(0.0D, 0.0D, 200.0D));
        aim.turn(Vec3.ZERO, 15.0D, 4.0D);
        assertEquals(200.0D, aim.target().length(), 1.0E-8D);

        // 调用真实命中接收模拟移到近处墙面，此后微调应保持新的 30 格距离。
        aim.acceptTarget(Vec3.ZERO, aim.direction(Vec3.ZERO).scale(30.0D));
        aim.turn(Vec3.ZERO, 0.2D, 0.1D);
        assertEquals(30.0D, aim.target().length(), 1.0E-8D);
    }

    @Test
    void missingTerrainDoesNotPinRenderDistanceEndpointWhileVehicleMoves() {
        Vec3 origin = new Vec3(0.0D, 200.0D, 0.0D);
        // 调用独立几何构造模拟八区块视距、向下五度但远处地形未加载的视线。
        Vec3 sight = direction(0.0D, 5.0D);
        Vec3 oldMissEndpoint = origin.add(sight.scale(128.0D));
        Vec3 movedOrigin = origin.add(0.0D, 0.0D, 100.0D);
        Vec3 oldBearing = oldMissEndpoint.subtract(movedOrigin);
        double oldPitch = -Math.toDegrees(Math.atan2(oldBearing.y, Math.hypot(oldBearing.x, oldBearing.z)));
        assertTrue(oldPitch > 22.0D, "旧逻辑前进一百格会从五度下俯漂到二十二度以上");

        for (int frames : new int[]{30, 60, 120, 144, 240}) {
            RVP_FocusLockAim aim = new RVP_FocusLockAim();
            // 调用正式射线接收：null 表示真实 MISS，不能把视距末端当成地面。
            aim.acceptRay(origin, sight, null);
            long revision = aim.revision();
            for (int frame = 0; frame <= frames; frame++) {
                double time = (double) frame / frames;
                Vec3 observer = origin.add(20.0D * time, 100.0D * time, 2000.0D * time);
                // 调用正式代理刷新，长距离前进、横移及爬升都不改变锁住的世界方向。
                aim.rebase(observer);
                assertDirection(aim.direction(observer), sight);
                assertEquals(revision, aim.revision());
                assertEquals(origin, aim.rayOrigin());
            }
            assertTrue(aim.isDirectionLocked());
        }
    }

    @Test
    void realDistantTerrainStillUsesFixedWorldPointAndCorrectParallax() {
        Vec3 origin = new Vec3(0.0D, 200.0D, 0.0D);
        Vec3 terrain = new Vec3(0.0D, 64.0D, 2500.0D);
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用真实命中接收，地形位于普通视距之外也应保持同一世界坐标。
        aim.acceptRay(origin, terrain.subtract(origin).normalize(), terrain);
        Vec3 moved = origin.add(300.0D, 50.0D, 500.0D);
        aim.rebase(moved);

        assertFalse(aim.isDirectionLocked());
        assertEquals(terrain, aim.target());
        // 调用方向断言，真实地面点不能被本次修复错误地改成完全不补偿视差的方向锁。
        assertDirection(aim.direction(moved), terrain.subtract(moved).normalize());
        assertTrue(aim.direction(moved).distanceTo(terrain.subtract(origin).normalize()) > 0.1D);
    }

    @Test
    void draggingFromNearWallIntoUnknownTerrainDropsFalseNearRange() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态模拟先命中近墙、再向天空/未加载远景拖动。
        aim.acceptRay(Vec3.ZERO, direction(0.0D, 0.0D), new Vec3(0.0D, 0.0D, 30.0D));
        aim.turn(Vec3.ZERO, 2.0D, -1.0D);
        Vec3 requested = aim.direction(Vec3.ZERO);
        aim.acceptRay(Vec3.ZERO, requested, null);
        Vec3 moved = new Vec3(0.0D, 20.0D, 200.0D);
        aim.rebase(moved);

        assertTrue(aim.isDirectionLocked());
        // 调用方向断言，已越过旧墙面距离仍能保持请求方向，不转向脚下或身后。
        assertDirection(aim.direction(moved), requested);
    }

    @Test
    void delayedTerrainReplyUsesOriginalRayWhileObserverKeepsMoving() {
        Vec3 origin = new Vec3(0.0D, 200.0D, 0.0D);
        Vec3 terrain = new Vec3(0.0D, 64.0D, 2500.0D);
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式未命中接收并捕获后台请求代次，查询期间模拟车辆继续前进。
        aim.acceptRay(origin, terrain.subtract(origin).normalize(), null);
        long requestRevision = aim.revision();
        Vec3 moved = origin.add(0.0D, 20.0D, 300.0D);
        aim.rebase(moved);
        assertTrue(aim.acceptTerrainTarget(requestRevision, terrain));

        assertFalse(aim.isDirectionLocked());
        assertEquals(terrain, aim.target());
        // 调用方向断言，结果对应最初的世界地面点，而不是把旧方向套在新观察位置上。
        assertDirection(aim.direction(moved), terrain.subtract(moved).normalize());
    }

    @Test
    void delayedTerrainReplyCannotOverrideNewMouseInputOrClearedLock() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式状态，在后台查询未完成时继续拖动，旧代次必须被拒绝。
        aim.acceptRay(Vec3.ZERO, direction(0.0D, 0.0D), null);
        long oldRevision = aim.revision();
        aim.turn(Vec3.ZERO, 15.0D, 5.0D);
        assertFalse(aim.acceptTerrainTarget(oldRevision, new Vec3(0.0D, 0.0D, 2500.0D)));
        assertDirection(aim.direction(Vec3.ZERO), direction(15.0D, 5.0D));

        long latestRevision = aim.revision();
        // 调用正式清理模拟解锁/换世界，已结束的会话不能被迟到结果重新锁上。
        aim.clear();
        assertFalse(aim.acceptTerrainTarget(latestRevision, new Vec3(0.0D, 0.0D, 2500.0D)));
        assertNull(aim.target());
    }

    @Test
    void missingInvalidOrBackwardTerrainReplyKeepsDirectionLock() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式未命中接收，数据缺失、非法坐标和身后命中都不能伪造地面。
        aim.acceptRay(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D), null);
        long revision = aim.revision();
        assertFalse(aim.acceptTerrainTarget(revision, null));
        assertFalse(aim.acceptTerrainTarget(revision, new Vec3(Double.NaN, 0.0D, 3000.0D)));
        assertFalse(aim.acceptTerrainTarget(revision, new Vec3(0.0D, 0.0D, -3000.0D)));
        assertTrue(aim.isDirectionLocked());
        // 调用方向断言，数据失败不会引起默认向下或向后的镜头修正。
        assertDirection(aim.direction(new Vec3(0.0D, 100.0D, 500.0D)), new Vec3(0.0D, 0.0D, 1.0D));
    }

    @Test
    void missingTerrainRetriesAfterOneSecondAndKeepsOriginalRayWhileMoving() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        Vec3 origin = new Vec3(0.5D, 1000.0D, 0.5D);
        Vec3 terrain = new Vec3(0.5D, 64.0D, 6000.5D);
        // 调用正式查询门，第一次失败不会逐刻重查，也不会永久停留在方向锁。
        aim.acceptRay(origin, terrain.subtract(origin).normalize(), null);
        long revision = aim.revision();
        assertTrue(aim.tryBeginTerrainQuery(0L));
        assertFalse(aim.acceptTerrainTarget(revision, null));
        assertFalse(aim.tryBeginTerrainQuery(999_999_999L));
        Vec3 moved = origin.add(50.0D, 20.0D, 500.0D);
        aim.rebase(moved);
        assertTrue(aim.tryBeginTerrainQuery(1_000_000_000L));
        assertEquals(origin, aim.rayOrigin());
        assertEquals(revision, aim.revision());
        // 调用正式结果接收，稍后才就绪的地形仍锁住最初瞄准的世界点。
        assertTrue(aim.acceptTerrainTarget(revision, terrain));
        assertFalse(aim.tryBeginTerrainQuery(2_000_000_000L));
        assertDirection(aim.direction(moved), terrain.subtract(moved).normalize());
    }

    @Test
    void newMouseRayCanQueryImmediatelyWithoutWaitingForOldRetryInterval() {
        RVP_FocusLockAim aim = new RVP_FocusLockAim();
        // 调用正式射线接收，鼠标选中新方向后立即查点，不继承旧射线的一秒等待。
        aim.acceptRay(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D), null);
        assertTrue(aim.tryBeginTerrainQuery(100L));
        long oldRevision = aim.revision();
        aim.turn(Vec3.ZERO, 10.0D, 5.0D);
        assertTrue(aim.tryBeginTerrainQuery(200L));
        assertFalse(aim.acceptTerrainTarget(oldRevision, new Vec3(0.0D, 0.0D, 2000.0D)));
        aim.clear();
        assertFalse(aim.tryBeginTerrainQuery(2_000_000_000L));
    }

    @Test
    void terrainColumnHitUsesSightSurfaceInsteadOfColumnBottom() {
        Vec3 origin = new Vec3(0.5D, 200.0D, 0.0D);
        Vec3 expected = new Vec3(0.5D, 100.0D, 2000.5D);
        Vec3 sight = expected.subtract(origin).normalize();
        // 调用正式远景求交，柱底在四十格，玩家瞄准的是一百格的上表面。
        Vec3 hit = RVP_FocusLockAim.terrainColumnIntersection(origin, sight, 48000.0D,
                0, 2000, 40, 100, 0);

        assertNotNull(hit);
        assertEquals(0.0D, hit.distanceTo(expected), 1.0E-7D);
        assertEquals(100.0D, hit.y, 1.0E-8D);
        // 调用方向断言，锁点必须留在原始视线上，不能改成远景柱底或柱中心。
        assertDirection(hit.subtract(origin).normalize(), sight);
    }

    @Test
    void coarseTerrainColumnHandlesNegativeCoordinatesAndRejectsOutOfRangeRay() {
        Vec3 origin = new Vec3(-7.0D, 160.0D, -2000.0D);
        Vec3 expected = new Vec3(-7.0D, 100.0D, -9.0D);
        Vec3 sight = expected.subtract(origin).normalize();
        // 调用正式求交验证四格宽远景柱在负坐标处向负无穷对齐。
        Vec3 hit = RVP_FocusLockAim.terrainColumnIntersection(origin, sight, 48000.0D,
                -7, -9, 80, 100, 2);
        assertNotNull(hit);
        assertEquals(0.0D, hit.distanceTo(expected), 1.0E-7D);

        // 调用正式边界检查，射程不足、倒置高度或非法尺寸应回退为未知距离。
        assertNull(RVP_FocusLockAim.terrainColumnIntersection(origin, sight, 500.0D,
                -7, -9, 80, 100, 2));
        assertNull(RVP_FocusLockAim.terrainColumnIntersection(origin, sight, 48000.0D,
                -7, -9, 100, 80, 2));
        assertNull(RVP_FocusLockAim.terrainColumnIntersection(origin, sight, 48000.0D,
                -7, -9, 80, 100, -1));
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
