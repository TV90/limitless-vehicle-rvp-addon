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
        unit.direction = new Vec3(0, 0, 1);
        var pack = Path.of("limitless_vehicle/rvp/data/rvp/weapons");
        var data = RVP_ProjectileSimulationWeapon.read(pack, "rvp:mi28_optical_tv").data();
        var atv = RVP_ProjectileSimulationWeapon.read(pack, "rvp:mi28_optical_atv").data();
        assertEquals(60f, data.getGuidanceData().getMaxLockHalfAngle());
        assertEquals(60f, atv.getGuidanceData().getMaxLockHalfAngle());
        assertEquals(120, data.getGuidanceData().getMaxGuidanceAngle());
        assertEquals(120, atv.getGuidanceData().getMaxGuidanceAngle());
        var weapon = new RVP_ProjectileWeapon(carrier, unit, 0, data, "optical_launch_probe", null);

        assertNull(RVP_OpticalLock.designate(gunner, unit, data, outside, null));
        assertSame(selected, RVP_OpticalLock.designate(gunner, unit, data, selected, null).entity());
        // 发射前同步炮塔姿态有变化，已确认的光学锁仍通过真实发射检查。
        unit.direction = new Vec3(-1, 0, 0);
        var shot = weapon.prepareShot(List.of(new AimContext()), gunner);
        assertNotNull(shot);
        assertSame(selected, shot.target());
        assertNotSame(nearest, shot.target());
        world.observeTick(35);
        assertSame(selected, weapon.prepareShot(List.of(new AimContext()), gunner).target());
        unit.direction = new Vec3(0, 0, 1);
        assertSame(nearest, RVP_OpticalLock.designate(gunner, unit, data, nearest, null).entity());
        assertSame(nearest, weapon.prepareShot(List.of(new AimContext()), gunner).target());
        assertSame(selected, shot.target());
        RVP_OpticalLock.designate(gunner, unit, data, null, null);
        assertNull(weapon.prepareShot(List.of(new AimContext()), gunner));
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
        private Scope() { super(0, null, null); }
        @Override public WeaponUnit getRootParentWeaponUnit() { return this; }
        @Override public WeaponUnitData.OpticalSightType getOpticalSightType() {
            return WeaponUnitData.OpticalSightType.OPTICAL_SCOPE;
        }
        @Override public Vec3 worldOpticalSightPosition(float partialTick) { return new Vec3(0, 9, 0); }
        @Override public Vec3 worldVec(float pitch, float yaw) { return direction; }
    }
}
