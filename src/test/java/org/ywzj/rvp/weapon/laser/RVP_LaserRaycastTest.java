package org.ywzj.rvp.weapon.laser;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.environment;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.read;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.write;

/** 生产射线在固定地形及原版包围盒上的近距回归。 */
public class RVP_LaserRaycastTest {

    @BeforeAll
    static void bootstrap() {
        RVP_ProjectileSimulationBootstrap.initialize();
    }

    @Test
    void floorBetweenMuzzleAndVisualStartStopsBeam() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        Vec3 muzzle = new Vec3(0, 0.25, 0);
        var beam = trace(level, muzzle, new Vec3(0, -1, 0));

        assertNotNull(beam.blockHit());
        assertEquals(new Vec3(0, 0, 0), beam.impactPoint());
        assertEquals(beam.renderStart(), beam.renderEnd());
        assertFalse(beam.isDrawable());
    }

    @Test
    void rangeIsMeasuredFromMuzzleAndEntityHitIsAtItsSurface() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        Vec3 muzzle = new Vec3(0, 4.5, 0);
        Vec3 look = new Vec3(0, 0, 1);
        assertEquals(muzzle.add(0, 0, 20), trace(level, muzzle, look).impactPoint());

        var boat = new Boat(EntityType.BOAT, level);
        boat.setPos(0, 4, 1);
        level.addObserver(boat);
        var beam = trace(level, muzzle, look);
        assertSame(boat, beam.hitEntity());
        assertEquals(muzzle.x, beam.impactPoint().x, 1e-6);
        assertEquals(muzzle.y, beam.impactPoint().y, 1e-6);
        assertEquals(boat.getBoundingBox().minZ, beam.impactPoint().z, 1e-6);
        assertFalse(trace(level, muzzle.add(boat.getBoundingBox().maxX + 0.05, 0, 0), look).hitSomething());
    }

    @Test
    void smokeBlocksAtItsBoundaryAndAtMuzzleInsideTheCloud() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var smoke = new RVP_SmokeEntity(RVP_Entities.RVP_SMOKE.get(), level);
        smoke.setBoundingBox(new AABB(-1, 3, 2, 1, 6, 5));
        level.addObserver(smoke);
        Vec3 look = new Vec3(0, 0, 1);

        var entering = trace(level, new Vec3(0, 4, 0), look);
        assertTrue(entering.smokeBlocked());
        assertEquals(new Vec3(0, 4, 2), entering.impactPoint());
        assertNull(entering.hitEntity());
        Vec3 inside = new Vec3(0, 4, 3);
        var blocked = trace(level, inside, look);
        assertTrue(blocked.smokeBlocked());
        assertEquals(inside, blocked.impactPoint());
        assertFalse(blocked.isDrawable());
    }

    private static RVP_LaserBeam trace(RVP_ProjectileSimulationLevel level, Vec3 muzzle, Vec3 look) {
        // 只隔离发射载具构造，实体查询和方块裁剪走游戏的生产入口。
        var origin = shooterFixture();
        return RVP_LaserRaycast.computeBeam(level, origin, null, muzzle, look, 20, 3);
    }

    /** 为原版 CollisionContext 补齐发射实体的局部状态。 */
    @SuppressWarnings("unchecked")
    public static FixedWingVehicle shooterFixture() {
        var origin = environment(FixedWingVehicle.class);
        write(Entity.class, origin, "position", Vec3.ZERO);
        var data = new SynchedEntityData(origin);
        data.define((EntityDataAccessor<Byte>) read(Entity.class, null, "DATA_SHARED_FLAGS_ID"), (byte) 0);
        write(Entity.class, origin, "entityData", data);
        origin.setBoundingBox(new AABB(-1, 3, -2, 1, 6, -1));
        return origin;
    }
}
