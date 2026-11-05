package org.ywzj.rvp.vehicle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaterniond;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.ywzj.rvp.client.state.RVP_FocusLockAim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** 执行真实稳定器虚方法，并回归相机过极点、机体倾斜和机械范围；无需启动游戏窗口。 */
class RVP_OpticalSightTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        // 调用已有无窗口环境初始化，真实 Minecraft 类型不使用测试替代实现。
        org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.initialize();
        OWNER = allocate(ArmorStand.class);
    }

    @Test
    void originalStabilizerReproducesYawStopButOpticalSubclassPassesNadir() throws Exception {
        OriginalStation old = allocate(OriginalStation.class);
        OpticalStation fixed = allocate(OpticalStation.class);
        // 调用同一初态设置，稳定器只需跨过半度就会暴露旧版左右掉头及止挡问题。
        prepare(old, 89.5F, 0.0F);
        prepare(fixed, 89.5F, 0.0F);
        old.setXAimRot(90.5F);
        fixed.setXAimRot(90.5F);
        old.updateWorldAimVec();
        fixed.updateWorldAimVec();
        old.runTick();
        fixed.runTick();
        assertTrue(old.getYAimRot() >= 119.0F, "必须能复现旧稳定器错误地撞向一百二十度左右止挡");
        assertEquals(90.5F, fixed.getXAimRot(), 0.02F);
        assertEquals(0.0F, fixed.getYAimRot(), 0.02F);

        for (float pitch = 91.0F; pitch <= 120.0F; pitch += 0.5F) {
            fixed.setXAimRot(pitch);
            fixed.updateWorldAimVec();
            fixed.runTick();
            assertEquals(pitch, fixed.getXRot(), 0.02F);
            assertEquals(0.0F, fixed.getYRot(), 0.02F);
        }
        assertTrue(fixed.worldVec().z < -0.49D, "一百二十度下俯必须实际看向机头后下方");
        for (int tick = 0; tick < 100; tick++) {
            fixed.runTick();
            assertEquals(120.0F, fixed.getXRot(), 0.02F);
            assertEquals(0.0F, fixed.getYRot(), 0.02F);
        }
    }

    @Test
    void verticalDirectionKeepsYawDespiteFloatingPointNoise() {
        for (float pitch : new float[]{-90.0F, 90.0F}) {
            for (float yaw : new float[]{-119.0F, -42.0F, 0.0F, 51.0F, 119.0F}) {
                for (double noise : new double[]{-1.0E-8D, 0.0D, 1.0E-8D}) {
                    // 调用生产解角，极点的浮点尾差不得把左右朝向随机翻转。
                    Vec2 angles = RVP_OpticalSightMath.localAngles(new Vec3(noise, pitch > 0 ? -1 : 1, -noise),
                            pitch, yaw, -120, 120, -120, 120);
                    assertEquals(pitch, angles.x, 1.0E-4F);
                    assertEquals(yaw, angles.y, 1.0E-4F);
                }
            }
        }
    }

    @Test
    void bothPitchBranchesRespectConfiguredMechanicalRange() {
        for (float pitch : new float[]{-115, -95, -89, 0, 89, 95, 115}) {
            for (float yaw : new float[]{-110, 0, 110}) {
                // 调用真实方向构造及生产解角，覆盖向上和向下跨极点，不依赖载具或武器 ID。
                Vec3 direction = new Vec3(rotation(pitch, yaw, 0).transform(new Vector3f(0, 0, 1)));
                Vec2 angles = RVP_OpticalSightMath.localAngles(direction, pitch, yaw,
                        -120, 120, -120, 120);
                assertEquals(pitch, angles.x, 1.0E-4F);
                assertEquals(yaw, angles.y, 1.0E-4F);
            }
        }
        // 等价的前向分支超出左右范围时，必须使用原配置允许的后下方俯仰分支。
        Vec2 reachable = RVP_OpticalSightMath.localAngles(
                new Vec3(rotation(120, 0, 0).transform(new Vector3f(0, 0, 1))), 60, 0, -40, 120, -120, 120);
        assertEquals(120.0F, reachable.x, 1.0E-4F);
        assertEquals(0.0F, reachable.y, 1.0E-4F);
    }

    @Test
    void cameraCrossesVerticalBothWaysWithoutHalfTurnOrInvalidAngles() {
        for (float yaw : new float[]{-179, -115, 0, 74, 179}) {
            for (float roll : new float[]{-45, 0, 38}) {
                RVP_OpticalSightMath.Angles previous = null;
                for (int step = 0; step <= 240; step++) {
                    float pitch = step <= 120 ? 60.0F + step * 0.5F : 120.0F - (step - 120) * 0.5F;
                    // 调用生产相机转换，逐帧穿过正下方并反向返回，包含精确九十度。
                    Quaternionf actual = rotation(pitch, yaw, roll);
                    RVP_OpticalSightMath.Angles angles = RVP_OpticalSightMath.cameraAngles(actual, previous);
                    assertPose(actual, rotation(angles.pitch(), angles.yaw(), angles.roll()));
                    if (previous != null) {
                        assertTrue(Math.abs(angles.pitch() - previous.pitch()) < 0.51F);
                        assertTrue(Math.abs(angles.yaw() - previous.yaw()) < 0.01F);
                        assertTrue(Math.abs(angles.roll() - previous.roll()) < 0.01F);
                    }
                    previous = angles;
                }
            }
        }
    }

    @Test
    void rolledAndTurningAircraftStillUsesCompleteInterpolatedPose() {
        for (float bodyPitch : new float[]{-35, 0, 25}) {
            for (float bodyRoll : new float[]{-70, 0, 60}) {
                RVP_OpticalSightMath.Angles previous = null;
                for (int step = 0; step <= 240; step++) {
                    // 调用完整姿态构造，飞机偏航/横滚与吊舱过正下方同时发生。
                    Quaternionf actual = rotation(bodyPitch, 170 + step * 0.1F, bodyRoll)
                            .mul(rotation(60 + step * 0.25F, 25, 0));
                    RVP_OpticalSightMath.Angles angles = RVP_OpticalSightMath.cameraAngles(actual, previous);
                    assertPose(actual, rotation(angles.pitch(), angles.yaw(), angles.roll()));
                    previous = angles;
                }
            }
        }
    }

    @Test
    void logicalCameraRollMatchesActualBarrelIncludingBeyondNadir() throws Exception {
        OpticalStation unit = allocate(OpticalStation.class);
        prepare(unit, 0, 0);
        unit.base = rotation(17, 122, -36);
        unit.xBarrelSelfRot = 1.5F;
        unit.yBarrelSelfRot = -2.0F;
        for (float pitch : new float[]{60, 89.9F, 90, 90.1F, 120}) {
            unit.setXRot(pitch);
            Vec2 direction = unit.worldRot(pitch + unit.xBarrelSelfRot, unit.yBarrelSelfRot);
            // 调用正式横滚入口核对逻辑相机，不能仅修画面而让选点射线仍向错误方向。
            assertPose(new Quaternionf(unit.base).mul(rotation(pitch + 1.5F, -2, 0)),
                    rotation(direction.x, direction.y, unit.worldZRot()));
        }
    }

    @Test
    void lockingBehindNadirAndFlyingOverGroundPointPreservesCompleteView() {
        // 调用生产相机转换，在一百一十度后下方锁点时保留原画面，不能立刻横滚半圈。
        Quaternionf initial = rotation(110, 15, 23);
        RVP_OpticalSightMath.Angles previous = RVP_OpticalSightMath.cameraAngles(initial,
                new RVP_OpticalSightMath.Angles(109, 15, 23));
        Vec3 initialDirection = new Vec3(initial.transform(new Vector3f(0, 0, 1)));
        RVP_OpticalSightMath.Angles locked = RVP_OpticalSightMath.pointCamera(initialDirection, previous);
        assertPose(initial, rotation(locked.pitch(), locked.yaw(), locked.roll()));

        for (float yaw : new float[]{-179, 0, 179, 240, 541}) {
            previous = new RVP_OpticalSightMath.Angles(60, yaw, 0);
            for (int step = 0; step <= 240; step++) {
                float pitch = 60.0F + step * 0.25F;
                // 调用真实世界方向到相机的转换，模拟飞过地面锁点，包含跨圈方位与正下方。
                Quaternionf expected = rotation(pitch, yaw, 0);
                Vec3 direction = new Vec3(expected.transform(new Vector3f(0, 0, 1)));
                RVP_OpticalSightMath.Angles next = RVP_OpticalSightMath.pointCamera(direction, previous);
                assertPose(expected, rotation(next.pitch(), next.yaw(), next.roll()));
                assertTrue(Math.abs(next.pitch() - previous.pitch()) < 0.26F);
                // 输入方向来自 float 姿态，极点附近会放大末位误差；仍须远小于可见跳转，更不能跳半圈。
                assertEquals(yaw, next.yaw(), 0.003F);
                assertTrue(Math.abs(next.yaw() - previous.yaw()) < 0.01F);
                assertEquals(0.0F, next.roll(), 1.0E-4F);
                previous = next;
            }
        }
    }

    @Test
    void screenRightAndDownKeepTheirMeaningAtAndBeyondBothPoles() {
        for (double pitch : new double[]{-110, -90, -89, 70, 89, 90, 91, 110}) {
            for (double yaw : new double[]{-175, 0, 99}) {
                for (double roll : new double[]{-170, -40, 0, 120}) {
                    // 调用生产画面轴输入，不能只检查相机静态姿态，必须核对鼠标的实际指向变化。
                    Quaterniond view = RVP_OpticalSightMath.rotation(pitch, yaw, roll);
                    Vec3 forward = RVP_OpticalSightMath.direction(view);
                    Vector3d rightAxis = view.transform(new Vector3d(-1, 0, 0));
                    Vector3d upAxis = view.transform(new Vector3d(0, 1, 0));
                    Vec3 right = new Vec3(rightAxis.x, rightAxis.y, rightAxis.z);
                    Vec3 up = new Vec3(upAxis.x, upAxis.y, upAxis.z);
                    Vec3 movedRight = RVP_OpticalSightMath.direction(RVP_OpticalSightMath.turnInView(view, forward, 0.2, 0));
                    Vec3 movedDown = RVP_OpticalSightMath.direction(RVP_OpticalSightMath.turnInView(view, forward, 0, 0.2));
                    assertTrue(movedRight.subtract(forward).dot(right) > 0.0034D);
                    assertEquals(0, movedRight.subtract(forward).dot(up), 1.0E-12D);
                    assertTrue(movedDown.subtract(forward).dot(up) < -0.0034D);
                    assertEquals(0, movedDown.subtract(forward).dot(right), 1.0E-12D);
                }
            }
        }
    }

    @Test
    void logDerivedFocusInputNoLongerSticksAtNinetyDegrees() {
        Vec3 origin = new Vec3(1300, 250, 100);
        // 使用日志卡点处的画面角与实际输入尺度；不是再次只验证一个静态的一百二十度姿态。
        Quaterniond view = RVP_OpticalSightMath.rotation(89.96, -130, 29.65);
        Vec3 start = RVP_OpticalSightMath.direction(view);
        RVP_FocusLockAim old = new RVP_FocusLockAim();
        RVP_FocusLockAim fixed = new RVP_FocusLockAim();
        old.acceptRay(origin, start, null);
        fixed.acceptRay(origin, start, null);
        double increment = 1.0000000894069698D * 0.33D;
        for (int input = 0; input < 12; input++) {
            old.turn(origin, 0, increment);
            // 调用正式输入及真实 MISS 接收，验证重新射线不会丢失完整画面姿态。
            fixed.turnInView(origin, view, 0, increment);
            fixed.acceptRay(origin, fixed.direction(origin), null);
        }
        assertTrue(old.direction(origin).distanceTo(new Vec3(0, -1, 0)) < 1.0E-8D,
                "旧世界角输入必须真实复现日志中的九十度卡点");
        Vec3 expected = RVP_OpticalSightMath.direction(new Quaterniond(view).rotateX(Math.toRadians(12 * increment)));
        assertEquals(0, fixed.direction(origin).distanceTo(expected), 1.0E-10D);
        assertTrue(fixed.direction(origin).distanceTo(new Vec3(0, -1, 0)) > 0.06D);
    }

    @Test
    void continuousFocusAccumulatesAtAllFrameRatesAndKeepsRealWorldPoint() {
        for (int frames : new int[]{30, 60, 144, 240}) {
            RVP_FocusLockAim aim = new RVP_FocusLockAim();
            Quaterniond view = RVP_OpticalSightMath.rotation(89, 37, -55);
            Vec3 initial = RVP_OpticalSightMath.direction(view);
            aim.acceptRay(Vec3.ZERO, initial, null);
            for (int frame = 0; frame < frames; frame++) {
                aim.turnInView(Vec3.ZERO, view, 0, 6.0D / frames);
                aim.acceptRay(Vec3.ZERO, aim.direction(Vec3.ZERO), null);
            }
            Vec3 expected = RVP_OpticalSightMath.direction(new Quaterniond(view).rotateX(Math.toRadians(6)));
            assertEquals(0, aim.direction(Vec3.ZERO).distanceTo(expected), 1.0E-10D);
            // 调用真实地面点接收，再移动观察点；新输入方式不能破坏原有世界点视差补偿。
            Vec3 ground = expected.scale(200);
            aim.acceptTarget(Vec3.ZERO, ground);
            Vec3 moved = new Vec3(4, 5, -3);
            Quaterniond followed = aim.viewRotation(moved, view);
            assertEquals(ground, aim.target());
            assertEquals(0, RVP_OpticalSightMath.direction(followed).distanceTo(ground.subtract(moved).normalize()), 1.0E-10D);
        }
    }

    @Test
    void normalSightMapsMouseToScreenAndOnlyUpdatesRequestedAngles() throws Exception {
        for (float pitch : new float[]{89, 90, 91, 110}) {
            OpticalStation unit = allocate(OpticalStation.class);
            prepare(unit, pitch, 0);
            Quaterniond view = RVP_OpticalSightMath.rotation(pitch, 0, 0);
            Vec3 before = unit.worldVec();
            // 调用正式武器站输入，包含基座逆变换、等价机械角选择与机械范围钳制。
            unit.turnInView(view, 0.2, 0);
            Vec3 after = unit.worldVec(unit.getXAimRot(), unit.getYAimRot());
            assertTrue(after.subtract(before).dot(new Vec3(-1, 0, 0)) > 0.003D,
                    "正下方和后下方的向右输入必须都指向画面右边");
            assertEquals(pitch, unit.getXRot(), 0.0F, "输入不能直接修改实际角而绕过限速");
            assertEquals(0, unit.getYRot(), 0.0F);
            assertTrue(unit.getXAimRot() >= -40 && unit.getXAimRot() <= 120);
            assertTrue(unit.getYAimRot() >= -120 && unit.getYAimRot() <= 120);
            unit.runTick();
            assertTrue(Math.abs(unit.getXRot() - pitch) <= 7.01F);
            assertTrue(Math.abs(unit.getYRot()) <= 7.01F);
        }
    }

    @Test
    void mechanicalYawAtNadirDoesNotRollStabilizedImage() {
        Quaterniond view = RVP_OpticalSightMath.rotation(90, 0, 0);
        for (int yaw = -120; yaw <= 120; yaw++) {
            Quaterniond mechanical = RVP_OpticalSightMath.rotation(90, yaw, 0);
            // 调用正式画面随动：机械在竖直处转动偏航，视线未变就不应把画面也转动同样角度。
            view = RVP_OpticalSightMath.followDirection(view, RVP_OpticalSightMath.direction(mechanical));
            Vector3d right = view.transform(new Vector3d(-1, 0, 0));
            assertTrue(right.distance(new Vector3d(-1, 0, 0)) < 1.0E-10D);
        }
    }

    @Test
    void normalSightUsesScreenAxesWithTheAircraftPoseRecordedInTheLog() throws Exception {
        for (float pitch : new float[]{80, 90, 110}) {
            OpticalStation unit = allocate(OpticalStation.class);
            // 实机日志的基座姿态，包含约九点五度机头上仰与非零世界航向，不能只测试水平飞机。
            unit.base = new Quaternionf(-0.048003845F, 0.8135707F, 0.06786156F, 0.5754939F).normalize();
            prepare(unit, pitch, 0);
            Quaterniond view = new Quaterniond(unit.base).rotateX(Math.toRadians(pitch));
            Vec3 before = unit.worldVec();
            Vector3d rightAxis = view.transform(new Vector3d(-1, 0, 0));
            Vector3d upAxis = view.transform(new Vector3d(0, 1, 0));
            unit.turnInView(view, 0.2, 0.1);
            Vec3 movement = unit.worldVec(unit.getXAimRot(), unit.getYAimRot()).subtract(before);
            assertTrue(movement.dot(new Vec3(rightAxis.x, rightAxis.y, rightAxis.z)) > 0.003);
            assertTrue(movement.dot(new Vec3(upAxis.x, upAxis.y, upAxis.z)) < -0.001);
        }
    }

    @Test
    void reversingScreenInputAtMechanicalStopDoesNotQueueAnImpossibleAngle() throws Exception {
        OpticalStation unit = allocate(OpticalStation.class);
        prepare(unit, 119.95F, 0);
        // 调用正式输入：请求越过上限后只保存可达的一百二十度，反向输入立即退出止挡。
        Quaterniond atStop = unit.turnInView(RVP_OpticalSightMath.rotation(119.95, 0, 0), 0, 10);
        assertEquals(120, unit.getXAimRot(), 0.0F);
        unit.turnInView(atStop, 0, -0.2);
        assertTrue(unit.getXAimRot() < 119.9F);
        assertEquals(119.95F, unit.getXRot(), 0.0F);
    }

    @Test
    void logDerivedSideStopMustNotChooseTheOppositeReachableBranch() throws Exception {
        OpticalStation unit = allocate(OpticalStation.class);
        unit.base = new Quaternionf(-0.0048511163F, 0.7926664F, 0.006307753F, 0.6096037F);
        prepare(unit, -0.2966311F, 119.99817F);
        unit.xRotMax = 179;
        unit.setXAimRot(0.9559173F);
        unit.setYAimRot(120);
        // 新日志 seq=54572：一格向下鼠标令偏航略超止挡；旧实现会跳成约 179/-60 度。
        Quaterniond view = RVP_OpticalSightMath.rotation(-1.5873504, -344.8523, 327.79114);
        unit.turnInView(view, 0, 1.0000000894069698D * 0.15D * 0.4375D);
        assertTrue(Math.abs(unit.getXAimRot() - 0.9559173F) < 0.2F, "碰左右边缘不能把俯仰翻到另一侧");
        assertEquals(120, unit.getYAimRot(), 0.001F);
    }

    @Test
    void logDerivedNadirStopMustNotSwingYawAcrossTheOtherSide() throws Exception {
        OpticalStation unit = allocate(OpticalStation.class);
        unit.base = new Quaternionf(-0.0048511163F, 0.7926664F, 0.006307753F, 0.6096037F);
        prepare(unit, 89.24744F, -21.974365F);
        unit.xRotMax = 179;
        unit.setXAimRot(89.14272F);
        unit.setYAimRot(-117.567F);
        // 新日志 seq=8408：鼠标向下本来只需停在 -120 度，旧实现却将目标偏航跳到 +58 度。
        Quaterniond view = RVP_OpticalSightMath.rotation(86.959885, -116.30191, 10.946412);
        unit.turnInView(view, 0, 1.0000000894069698D * 0.15D * 0.4375D);
        assertTrue(Math.abs(unit.getXAimRot() - 89.14272F) < 0.2F);
        assertEquals(-120, unit.getYAimRot(), 0.001F, "正下方附近不能为绕过止挡而横转近半圈");
    }

    @Test
    void pitchNearOneEightyClampsContinuouslyAndReversesImmediately() throws Exception {
        OpticalStation unit = allocate(OpticalStation.class);
        prepare(unit, 178.95F, 0);
        unit.xRotMax = 179;
        // 调用正式输入，跨过角度表示的正负一百八十度边界后仍应停在 +179，而非钳到 -40。
        Quaterniond atStop = unit.turnInView(RVP_OpticalSightMath.rotation(178.95, 0, 0), 0, 10);
        assertEquals(179, unit.getXAimRot(), 0.0F);
        assertEquals(0, unit.getYAimRot(), 0.01F);
        unit.turnInView(atStop, 0, -0.2);
        assertTrue(unit.getXAimRot() < 178.9F && unit.getXAimRot() > 178.6F);
    }

    @Test
    void localAnglesPreserveCurrentBranchEvenIfAnotherBranchFitsTheLimits() {
        for (float side : new float[]{-1, 1}) {
            Vec3 direction = RVP_OpticalSightMath.direction(RVP_OpticalSightMath.rotation(1.02, side * 120.04, 0));
            // 调用正式解角：先保持连续机械路径，再由调用方钳制；不能先换成可达的远端等价角。
            Vec2 local = RVP_OpticalSightMath.localAngles(direction, 0.95F, side * 120,
                    -40, 179, -120, 120);
            assertEquals(1.02, local.x, 0.001);
            assertEquals(side * 120.04, local.y, 0.001);
        }
    }

    /** 以本体相同的 Y-X-Z 顺序构造姿态，参数均为度。 */
    private static Quaternionf rotation(float pitch, float yaw, float roll) {
        // 用双精度三角函数构造参考姿态，不能让 float 半角余弦的舍入误差掩盖解角问题。
        return new Quaternionf(new Quaterniond().rotateY(Math.toRadians(-yaw))
                .rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll)));
    }

    @Test
    void disabledStabilizerKeepsLocalAimWhileAircraftTurnsAcrossTicks() throws Exception {
        OpticalStation unit = allocate(OpticalStation.class);
        prepare(unit, 35, 12);
        Field stabilizer = WeaponUnit.class.getDeclaredField("withStabilizer");
        stabilizer.setAccessible(true); stabilizer.setBoolean(unit, false);
        for (int tick = 0; tick < 60; tick++) {
            unit.base = rotation(0, tick * 0.5F, tick * 0.2F);
            // 调用真实子类逻辑刻，关闭稳定器后机械目标应相对机体保持不变。
            unit.runTick();
            assertEquals(35, unit.getXRot(), 0.002);
            assertEquals(12, unit.getYRot(), 0.002);
        }
    }

    @Test
    void ordinaryWeaponUnitsNeverEnterTheNewOpticalRayEvenWithOverVerticalRange() throws Exception {
        OriginalStation ordinary = allocate(OriginalStation.class);
        prepare(ordinary, 100, 20);
        // 调用正式资格门，旧站即使俯仰超过九十度也不进入新相机/选点通道。
        assertNull(org.ywzj.rvp.client.state.RVP_ClientOpticalSight.sampleRay(ordinary, 0.5F));
        assertNull(org.ywzj.rvp.client.state.RVP_ClientOpticalSight.sampleRay(null, 1));
    }

    @Test
    void targetAngleMirrorKeepsTheRequestedWorldDirectionWhileAircraftTurns() throws Exception {
        OpticalStation unit = allocate(OpticalStation.class);
        prepare(unit, 70, 0);
        Vec3 requested = unit.worldVec();
        for (int tick = 0; tick < 40; tick++) {
            unit.base = rotation((float) Math.sin(tick * 0.1) * 6, tick * 0.3F, (float) Math.sin(tick * 0.15) * 15);
            // 调用真实转动与目标字段镜像，目标圈读取也应保持同一世界方向。
            unit.runTick();
            assertTrue(unit.worldVec(unit.getXAimRot(), unit.getYAimRot()).distanceTo(requested) < 1.0E-5);
        }
    }

    /** 同时核对前、右、上三个方向，不能只验视线而漏掉画面翻滚。 */
    private static void assertPose(Quaternionf expected, Quaternionf actual) {
        for (Vector3f axis : new Vector3f[]{new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}) {
            float error = expected.transform(new Vector3f(axis)).distance(actual.transform(new Vector3f(axis)));
            assertTrue(error < 2.0E-4F, () -> "axis=" + axis + ", error=" + error
                    + ", expected=" + expected + ", actual=" + actual);
        }
    }

    /** 仅设置环境与稳定器初态，实际 tickRot 和角度转换全部执行生产代码。 */
    private static void prepare(WeaponUnit unit, float pitch, float yaw) throws Exception {
        unit.needPower = false;
        unit.xRotMin = -40; unit.xRotMax = 120; unit.yRotMin = -120; unit.yRotMax = 120;
        unit.xRotSpeed = unit.yRotSpeed = 7;
        unit.setXRot(pitch); unit.setYRot(yaw); unit.setXAimRot(pitch); unit.setYAimRot(yaw);
        Field stabilizer = WeaponUnit.class.getDeclaredField("withStabilizer");
        stabilizer.setAccessible(true);
        stabilizer.setBoolean(unit, true);
        Field previousDirection = WeaponUnit.class.getDeclaredField("worldVec");
        previousDirection.setAccessible(true);
        previousDirection.set(unit, unit.worldVec());
        // 调用正式意图方向刷新，不复制稳定器使用的内部方向计算。
        unit.updateWorldAimVec();
    }

    /** 跳过实体/武器站构造期的世界与网络环境，避免为几何回归启动 Minecraft。 */
    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }

    /** 原始站仅隔离基座/乘员环境，用于证明回归确实能触发旧错误。 */
    private static class OriginalStation extends WeaponUnit {
        private OriginalStation() { super(0, null, null); }
        @Override public Quaternionf baseRot() { return new Quaternionf(); }
        @Override public boolean isDestroyed() { return false; }
        @Override public LivingEntity getOwner() { return OWNER; }
        /** 调用本体真实稳定器，暴露给无窗口测试。 */
        void runTick() { super.tickRot(); }
    }

    /** 新站仅隔离基座/乘员环境，不覆写本次修复的任何计算。 */
    private static class OpticalStation extends RVP_OpticalWeaponUnit {
        /** 可切换的飞机基座姿态；null 表示水平。 */ private Quaternionf base;
        private OpticalStation() { super(0, null, null); }
        @Override public Quaternionf baseRot() { return base == null ? new Quaternionf() : new Quaternionf(base); }
        @Override public boolean isDestroyed() { return false; }
        @Override public LivingEntity getOwner() { return OWNER; }
        /** 调用继承的本体真实稳定器，不能在测试中复制一套循环。 */
        void runTick() { super.tickRot(); }
    }

    /** 稳定器要求存在乘员；仅提供非空实体，不创建窗口或世界。 */
    private static LivingEntity OWNER;

}
