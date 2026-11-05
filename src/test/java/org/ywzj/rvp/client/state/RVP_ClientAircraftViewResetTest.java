package org.ywzj.rvp.client.state;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMath;
import org.ywzj.vehicle.client.handler.FirstPersonHandler;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 执行真实退出复位，覆盖机头方向、座舱倾斜、两帧插值、重复切换与非飞机隔离。 */
class RVP_ClientAircraftViewResetTest {
    /** 测试结束恢复原本的相机横滚，避免影响其他客户端回归。 */
    private float previousRoll;

    @BeforeAll static void bootstrap() { initialize(); }
    @BeforeEach void rememberRoll() { previousRoll = FirstPersonHandler.zRot; }
    @AfterEach void restore() {
        FirstPersonHandler.zRot = previousRoll;
        RVP_ClientOpticalSight.clearView();
        RVP_ClientFocusLock.clear();
    }

    @Test
    void thirdPersonResetsToCurrentNoseAndClearsBothInterpolationEndpoints() {
        Aircraft aircraft = environment(Aircraft.class);
        aircraft.yaw = 250;
        aircraft.pose = new Quaternionf(RVP_OpticalSightMath.rotation(15, 250, 40));
        Fixture f = fixture(aircraft, 0);
        assertTrue(RVP_ClientAircraftViewReset.reset(f.local, f.player, false, false));
        assertEquals(25, f.local.cameraAimRotX, 0.001F);
        assertEquals(-110, f.local.cameraAimRotY, 0.001F);
        assertEquals(0, f.local.cameraAimRotZ, 0.0F);
        assertResetEndpoints(f);
        // 调用本体实际驾驶视线解析，扣除观察偏角后应沿机头，不再把吊舱方向作为飞行输入。
        assertDirection(f.local.cameraDirection(LocalVehiclePlayer.CAMERA_UPWARD_ANGLE), aircraft.pose);
        assertEquals(new Vec3(1, 2, 3), f.unit.lock, "退出视角不能清除原有焦点目标");
    }

    @Test
    void operatorRestoresAircraftDefaultPoseIncludingBank() {
        Aircraft aircraft = environment(Aircraft.class);
        aircraft.yaw = 72;
        aircraft.pose = new Quaternionf(RVP_OpticalSightMath.rotation(-12, 72, 37));
        Fixture f = fixture(aircraft, 0);
        assertTrue(RVP_ClientAircraftViewReset.reset(f.local, f.player, false, true));
        Quaternionf expected = new Quaternionf(aircraft.pose)
                .rotateX((float) Math.toRadians(LocalVehiclePlayer.CAMERA_UPWARD_ANGLE));
        // 调用本体真实相机旋转，核对全部轴；不能只验视线而漏掉从吊舱残留的横滚。
        assertPose(expected, f.local.cameraRotation());
        assertDirection(f.local.cameraDirection(LocalVehiclePlayer.CAMERA_UPWARD_ANGLE), aircraft.pose);
        assertResetEndpoints(f);
    }

    @Test
    void helicopterAndSeatDefaultYawUseTheSameReset() {
        Helicopter helicopter = environment(Helicopter.class);
        helicopter.yaw = 359;
        helicopter.pose = new Quaternionf(RVP_OpticalSightMath.rotation(0, 359, 0));
        Fixture f = fixture(helicopter, 30);
        assertTrue(RVP_ClientAircraftViewReset.reset(f.local, f.player, false, false));
        assertEquals(29, f.local.cameraAimRotY, 0.001F);
        assertEquals(30, f.local.playerLocalYRot, 0.001F);
        assertDirection(f.local.cameraDirection(LocalVehiclePlayer.CAMERA_UPWARD_ANGLE),
                new Quaternionf(helicopter.pose).rotateY((float) Math.toRadians(-30)));
    }

    @Test
    void enteringScopeAndGroundVehiclesKeepTheirOriginalView() {
        Aircraft aircraft = environment(Aircraft.class);
        Fixture entering = fixture(aircraft, 0);
        assertFalse(RVP_ClientAircraftViewReset.reset(entering.local, entering.player, true, false));
        assertEquals(110, entering.local.cameraAimRotX);
        GroundVehicle ground = environment(GroundVehicle.class);
        Fixture groundScope = fixture(ground, 0);
        assertFalse(RVP_ClientAircraftViewReset.reset(groundScope.local, groundScope.player, false, false));
        assertEquals(110, groundScope.local.cameraAimRotX);
    }

    @Test
    void leavingVehicleDoesNotResetPlayerAsIfStillInAircraft() {
        Fixture f = fixture(environment(Aircraft.class), 0);
        f.player.ride = null;
        assertFalse(RVP_ClientAircraftViewReset.reset(f.local, f.player, false, false));
        assertEquals(110, f.local.cameraAimRotX);
        assertEquals(110, f.player.getXRot());
    }

    @Test
    void repeatedScopeExitResetsOnceAndReleasesOldOpticalPose() {
        Aircraft aircraft = environment(Aircraft.class);
        aircraft.yaw = 40;
        aircraft.pose = new Quaternionf(RVP_OpticalSightMath.rotation(0, 40, 0));
        Fixture f = fixture(aircraft, 0);
        for (int repeat = 0; repeat < 3; repeat++) {
            f.local.viewType = LocalVehiclePlayer.ViewType.SCOPE;
            f.local.cameraAimRotX = 110;
            f.local.cameraAimRotY = -270;
            // 仅注入显示缓存环境，实际退出清理必须由生产复位入口完成。
            write(RVP_ClientOpticalSight.class, null, "previous", new RVP_OpticalSightMath.Angles(110, -270, 85));
            write(RVP_ClientOpticalSight.class, null, "inputRotation", new Quaterniond());
            assertTrue(RVP_ClientAircraftViewReset.reset(f.local, f.player, false, false));
            assertNull(read(RVP_ClientOpticalSight.class, null, "previous"));
            assertNull(read(RVP_ClientOpticalSight.class, null, "inputRotation"));
            assertResetEndpoints(f);
            // 模拟既有注入点之后本体写入最终模式；退出后的正常鼠标环视不应再被强制归正。
            f.local.viewType = LocalVehiclePlayer.ViewType.THIRD_PERSON;
            f.local.cameraAimRotY = 91;
            assertFalse(RVP_ClientAircraftViewReset.reset(f.local, f.player, false, false));
            assertEquals(91, f.local.cameraAimRotY);
        }
    }

    /** 同时核对当前/上一帧及旧插值目标，确保首帧和下一刻都不会拉回吊舱角度。 */
    private static void assertResetEndpoints(Fixture f) {
        assertEquals(f.local.cameraAimRotX, f.local.cameraAimRotXO);
        assertEquals(f.local.cameraAimRotY, f.local.cameraAimRotYO);
        assertEquals(f.local.cameraAimRotZ, f.local.cameraAimRotZO);
        assertEquals(f.local.cameraAimRotX, f.player.getXRot());
        assertEquals(f.local.cameraAimRotY, f.player.getYRot());
        assertEquals(f.player.getXRot(), f.player.xRotO);
        assertEquals(f.player.getYRot(), f.player.yRotO);
        assertEquals(f.local.cameraAimRotX, f.local.playerLerpXRot);
        assertEquals(f.local.cameraAimRotY, f.local.playerLerpYRot);
        assertEquals(0, f.local.playerLerpSteps);
        assertEquals(f.local.cameraAimRotZ, FirstPersonHandler.zRot);
    }

    /** 核对驾驶使用的实际前向轴。 */
    private static void assertDirection(Vec3 actual, Quaternionf expected) {
        Vector3f forward = expected.transform(new Vector3f(0, 0, 1));
        assertTrue(actual.distanceTo(new Vec3(forward)) < 2.0E-4D);
    }

    /** 核对完整相机姿态。 */
    private static void assertPose(Quaternionf expected, Quaternionf actual) {
        for (Vector3f axis : new Vector3f[]{new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}) {
            assertTrue(expected.transform(new Vector3f(axis)).distance(actual.transform(new Vector3f(axis))) < 2.0E-4F);
        }
    }

    /** 只隔离世界与联网环境，原版角度存储和本体相机方法照常执行。 */
    private static Fixture fixture(AbstractVehicle vehicle, float seatYaw) {
        TestStation unit = environment(TestStation.class);
        unit.vehicle = vehicle;
        unit.seatYaw = seatYaw;
        unit.lock = new Vec3(1, 2, 3);
        TestPlayer player = environment(TestPlayer.class);
        player.ride = vehicle;
        player.setXRot(110); player.setYRot(-270);
        LocalVehiclePlayer local = new LocalVehiclePlayer();
        local.vehicle = vehicle;
        local.seat = new AbstractVehicle.Seat(0, unit);
        local.viewType = LocalVehiclePlayer.ViewType.SCOPE;
        local.cameraAimRotX = 110; local.cameraAimRotY = -270; local.cameraAimRotZ = 85;
        local.cameraAimRotXO = -20; local.cameraAimRotYO = 120; local.cameraAimRotZO = -170;
        local.playerLerpSteps = 8;
        local.playerLerpXRot = -80; local.playerLerpYRot = -120;
        return new Fixture(local, player, unit);
    }

    /** 无窗口测试会话。 */
    private record Fixture(
            /** 本体视角状态。 */ LocalVehiclePlayer local,
            /** 当前本地乘员。 */ TestPlayer player,
            /** 乘员所操作的站。 */ TestStation unit) {}

    /** 只提供机体姿态，不替代视角复位。 */
    private static class Aircraft extends FixedWingVehicle {
        /** 当前机体完整姿态。 */ private Quaternionf pose;
        /** 当前机头航向。 */ private float yaw;
        private Aircraft() { super(null, null); }
        @Override public Quaternionf rotYXZ() { return new Quaternionf(pose); }
        @Override public float getYRot() { return yaw; }
    }

    /** 旋翼飞机同样需要恢复机头/座位默认方向。 */
    private static class Helicopter extends RotaryWingVehicle {
        /** 当前机体完整姿态。 */ private Quaternionf pose;
        /** 当前机头航向。 */ private float yaw;
        private Helicopter() { super(null, null); }
        @Override public Quaternionf rotYXZ() { return new Quaternionf(pose); }
        @Override public float getYRot() { return yaw; }
    }

    /** 地面载具只用于证明不会被本次行为覆盖。 */
    private static class GroundVehicle extends WheeledVehicle {
        private GroundVehicle() { super(null, null); }
    }

    /** 仅隔离乘坐关系，角度仍由 Player/Entity 原方法保存。 */
    private static class TestPlayer extends Player {
        /** 当前真实坐骑。 */ private Entity ride;
        private TestPlayer() { super(null, BlockPos.ZERO, 0, new GameProfile(UUID.randomUUID(), "view_reset")); }
        @Override public Entity getVehicle() { return ride; }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
    }

    /** 仅提供现有座位方向与乘坐载具，不写任何相机替代逻辑。 */
    private static class TestStation extends WeaponUnit {
        /** 所属载具。 */ private AbstractVehicle vehicle;
        /** 座位的局部默认朝向。 */ private float seatYaw;
        /** 原有焦点，复位后应保留。 */ private Vec3 lock;
        private TestStation() { super(0, null, null); }
        @Override public AbstractVehicle getVehicle() { return vehicle; }
        @Override public float getSeatRot() { return vehicle.getYRot() + seatYaw; }
        @Override public Vec3 getFocusLockPos() { return lock; }
    }
}
