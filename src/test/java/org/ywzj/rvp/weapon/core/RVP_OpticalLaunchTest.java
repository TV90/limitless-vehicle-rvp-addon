package org.ywzj.rvp.weapon.core;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.rvp.guidance.RVP_OpticalLock;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationWeapon;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

class RVP_OpticalLaunchTest {
    @BeforeAll static void bootstrap() { initialize(); }

    @Test void realTvLaunchGateUsesTheExplicitLockUntilItIsReplaced() throws Exception {
        var world = RVP_ProjectileSimulationLevel.create(0, null);
        var gunner = environment(Gunner.class);
        gunner.setUUID(UUID.randomUUID());
        write(Entity.class, gunner, "level", world);
        write(Entity.class, gunner, "position", new Vec3(0, 9, 0));
        var carrier = vehicle(world, 0, 0, 1001);
        var nearest = vehicle(world, 0, 30, 1002);
        var selected = vehicle(world, 51, 35, 1003);
        var outside = vehicle(world, 70, 29, 1004);
        var unit = environment(Scope.class);
        write(PartUnit.class, unit, "vehicle", carrier);
        var stationData = environment(WeaponUnitData.class);
        write(WeaponUnitData.class, stationData, "zoomMin", 1f);
        write(WeaponUnitData.class, stationData, "zoomMax", 12f);
        write(PartUnit.class, unit, "data", stationData);
        unit.direction = new Vec3(0, 0, 1);
        var pack = Path.of("limitless_vehicle/rvp/data/rvp/weapons");
        var data = RVP_ProjectileSimulationWeapon.read(pack, "rvp:mi28_optical_tv").data();
        var atv = RVP_ProjectileSimulationWeapon.read(pack, "rvp:mi28_optical_atv").data();
        assertEquals(60f, data.getGuidanceData().getMaxLockHalfAngle());
        assertEquals(60f, atv.getGuidanceData().getMaxLockHalfAngle());
        assertEquals(120, data.getGuidanceData().getMaxGuidanceAngle());
        assertEquals(120, atv.getGuidanceData().getMaxGuidanceAngle());
        var weapon = new RVP_ProjectileWeapon(carrier, unit, 0, data, "optical_launch_probe", null);

        assertNull(RVP_OpticalLock.designate(gunner, unit, data, outside, null, true, false));
        assertSame(selected, RVP_OpticalLock.designate(gunner, unit, data, selected, null, true, false).entity());
        // 发射前同步炮塔姿态有变化，已确认的光学锁仍通过真实发射检查。
        unit.direction = new Vec3(-1, 0, 0);
        var shot = weapon.prepareShot(List.of(new AimContext()), gunner);
        assertNotNull(shot);
        assertSame(selected, shot.target());
        assertNotSame(nearest, shot.target());
        world.observeTick(35);
        assertSame(selected, weapon.prepareShot(List.of(new AimContext()), gunner).target());
        unit.direction = new Vec3(0, 0, 1);
        assertSame(nearest, RVP_OpticalLock.designate(gunner, unit, data, nearest, null, false, false).entity());
        assertSame(nearest, weapon.prepareShot(List.of(new AimContext()), gunner).target());
        assertSame(selected, shot.target());
        RVP_OpticalLock.designate(gunner, unit, data, null, null, false, false);
        assertNull(weapon.prepareShot(List.of(new AimContext()), gunner));

        // 距离和可见度相同，只提高瞄具倍率即可通过服务端实体锁定门槛。
        var distant = vehicle(world, 0, 300, 1005);
        assertNull(RVP_OpticalLock.designate(gunner, unit, data, distant, null, false, false));
        var zoomedLock = RVP_OpticalLock.designate(gunner, unit, data, distant, null, true, false);
        assertNotNull(zoomedLock);
        assertSame(distant, zoomedLock.entity());
        assertTrue(zoomedLock.confidence() >= data.getGuidanceData().getRecognitionLockThreshold());
        assertSame(distant, weapon.prepareShot(List.of(new AimContext()), gunner).target());

        // 白光和无热成像设备的请求都不能在漆黑场景锁定目标。
        world.setOpticalTestLight(0.5f, 0, 0);
        assertNull(RVP_OpticalLock.designate(gunner, unit, data, nearest, null, false, false));
        assertNull(RVP_OpticalLock.designate(gunner, unit, data, nearest, null, true, true));
        unit.thermalImaging = true;
        assertNotNull(RVP_OpticalLock.designate(gunner, unit, data, nearest, null, false, true));
        assertTrue(RVP_OpticalLock.usesThermal(gunner, unit, data));
        assertSame(nearest, weapon.prepareShot(List.of(new AimContext()), gunner).target());

        // 有热成像也不能把像素不足的固定设备识别成已锁定目标。
        var small = vehicle(world, 0, 85, 1006);
        small.setBoundingBox(new AABB(-0.28, 8.72, 84.72, 0.28, 9.28, 85.28));
        assertNull(RVP_OpticalLock.designate(gunner, unit, data, small, null, false, true));
        assertSame(small, RVP_OpticalLock.designate(gunner, unit, data, small, null, true, true).entity());

        // 机体很薄，翼展在视野中仍足以通过低倍率锁定。
        world.setOpticalTestLight(0f, 15, 0);
        var aircraft = vehicle(world, 0, 130, 1007);
        aircraft.setBoundingBox(new AABB(-4, 8.9, 129, 4, 9.1, 131));
        assertSame(aircraft, RVP_OpticalLock.designate(gunner, unit, data, aircraft, null, false, false).entity());

        // 只有射线命中真实方块且光照不足才判为地形无法辨认。
        var ground = new Vec3(0.5, 0, 30.5);
        world.setOpticalTestLight(0.5f, 0, 0);
        var darkGround = RVP_OpticalLock.tryDesignate(gunner, unit, data, null, ground, false, false);
        assertNull(darkGround.lock());
        assertTrue(darkGround.groundTooDark());
        var emptySky = RVP_OpticalLock.tryDesignate(gunner, unit, data, null, ground.add(0, 20, 0), false, false);
        assertNull(emptySky.lock());
        assertFalse(emptySky.groundTooDark());
        var darkEntity = RVP_OpticalLock.tryDesignate(gunner, unit, data, nearest, null, false, false);
        assertNull(darkEntity.lock());
        assertFalse(darkEntity.groundTooDark());
        assertNotNull(RVP_OpticalLock.tryDesignate(gunner, unit, data, null, ground, false, true).lock());
    }

    @Test void serverReloadUpdatesAndSpawnRefillClearTheTimer() throws Exception {
        var world = RVP_ProjectileSimulationLevel.create(0, null);
        var vehicle = vehicle(world, 0, 0, 1008);
        var data = RVP_ProjectileSimulationWeapon.read(Path.of("limitless_vehicle/rvp/data/rvp/weapons"),
                "rvp:mi28_optical_tv").data();
        var weapon = new RVP_ProjectileWeapon(vehicle, null, 0, data, "reload_probe", null) {
            @Override public net.minecraft.sounds.SoundEvent getReloadSound() {
                throw new AssertionError("服务端不应解析换弹音效");
            }
        };

        weapon.ywzj_rvp$setReloadTime(20);
        assertEquals(20, weapon.getReloadTime());
        weapon.ywzj_rvp$clearReloadState();
        assertEquals(0, weapon.getReloadTime());
        weapon.ywzj_rvp$setReloadTime(1);
        weapon.ywzj_rvp$setReloadTime(0);
        assertEquals(0, weapon.getReloadTime());
    }

    private static Vehicle vehicle(RVP_ProjectileSimulationLevel level, int x, int z, int id) {
        var target = environment(Vehicle.class);
        write(Entity.class, target, "level", level);
        write(Entity.class, target, "position", new Vec3(x, 8, z));
        target.setBoundingBox(new AABB(x - 1, 8, z - 2, x + 1, 10, z + 2));
        target.setUUID(UUID.randomUUID());
        target.setId(id);
        level.addObserver(target);
        return target;
    }

    private static final class Vehicle extends WheeledVehicle {
        private Vehicle() { super(null, null); }
        @Override public ResourceLocation getVehicleId() { return ResourceLocation.tryParse("rvp:optical_test"); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPickable() { return true; }
    }

    private static final class Gunner extends ServerPlayer {
        private Gunner() { super(null, null, null); }
        @Override public void displayClientMessage(Component text, boolean actionBar) {}
        @Override public boolean isDescending() { return false; }
        @Override public net.minecraft.world.item.ItemStack getMainHandItem() {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    private static final class Scope extends WeaponUnit {
        private Vec3 direction;
        private boolean thermalImaging;
        @Override public boolean withThermalImager() { return thermalImaging; }
        private Scope() { super(0, null, null); }
        @Override public WeaponUnit getRootParentWeaponUnit() { return this; }
        @Override public WeaponUnitData.OpticalSightType getOpticalSightType() {
            return WeaponUnitData.OpticalSightType.OPTICAL_SCOPE;
        }
        @Override public Vec3 worldOpticalSightPosition(float partialTick) { return new Vec3(0, 9, 0); }
        @Override public Vec3 worldVec(float pitch, float yaw) { return direction; }
    }
}
