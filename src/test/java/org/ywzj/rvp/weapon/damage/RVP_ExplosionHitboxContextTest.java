package org.ywzj.rvp.weapon.damage;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;
import org.ywzj.vehicle.vehicle.structure.OBB;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 直接命中优先、最近表面、未配置骨遮挡及嵌套窗口的生产算法回归。 */
class RVP_ExplosionHitboxContextTest {
    private static RVP_NamedVehicleObbs.Box box(String name, float x, float y, float z, float ex, float ey, float ez) {
        return new RVP_NamedVehicleObbs.Box(new RVP_NamedVehicleObbs.Key(name, 0),
                new OBB(new Vector3f(x, y, z), new Vector3f(ex, ey, ez), new Quaternionf()));
    }

    @Test void directHitUsesItsBoneInsteadOfTheBoneNearestTheShiftedBlastCenter() {
        UUID vehicle = UUID.randomUUID();
        var body = box("body", 0, 0, 0, 3, 1, 4);
        var top = box("top", 0, 2, 0, 1, .1F, 1);
        var impact = new RVP_ExplosionHitboxContext.Impact(vehicle, top.key(), 20);
        assertEquals(1.5F, RVP_ExplosionHitboxContext.factor(vehicle, List.of(body, top), Map.of("top", 1.5F), Vec3.ZERO, impact));
        assertEquals(1F, RVP_ExplosionHitboxContext.factor(UUID.randomUUID(), List.of(body, top), Map.of("top", 1.5F), Vec3.ZERO, impact));
    }

    @Test void splashUsesNearestSurfaceAmongAllBonesRatherThanNearestConfiguredCenter() {
        var body = box("body", 0, 0, 0, 10, 1, 1);
        var top = box("top", 8, 4, 0, .5F, .1F, .5F);
        Vec3 point = new Vec3(8, 1.1, 0);
        assertEquals("body", RVP_NamedVehicleObbs.nearest(List.of(top, body), point).key().bone());
        assertEquals(1F, RVP_ExplosionHitboxContext.factor(UUID.randomUUID(), List.of(top, body), Map.of("top", 1.5F), point, null));
        assertEquals(1.5F, RVP_ExplosionHitboxContext.factor(UUID.randomUUID(), List.of(top, body), Map.of("top", 1.5F), new Vec3(8, 4.2, 0), null));
    }

    @Test void rotatedSurfaceAndOverlappingThinBoneHaveDeterministicSelection() {
        var rotated = new RVP_NamedVehicleObbs.Box(new RVP_NamedVehicleObbs.Key("tube", 0),
                new OBB(new Vector3f(), new Vector3f(4, .2F, .2F), new Quaternionf().rotateY((float) Math.PI / 2)));
        var other = box("body", 3, 0, 0, .5F, 1, .5F);
        assertEquals(rotated, RVP_NamedVehicleObbs.nearest(List.of(other, rotated), new Vec3(0, 0, -4.1)));
        var hull = box("body", 0, 0, 0, 3, 2, 3);
        var roof = box("top", 0, 1.9F, 0, 1, .1F, 1);
        assertEquals(roof, RVP_NamedVehicleObbs.nearest(List.of(hull, roof), new Vec3(0, 1.95, 0)));
        assertEquals(roof, RVP_NamedVehicleObbs.raycast(List.of(hull, roof), new Vec3(0, 4, 0), new Vec3(0, -2, 0)).box());
    }

    @Test void nestedAndThrowingExplosionCannotLeakOuterHitbox() {
        var outer = new RVP_ExplosionHitboxContext.Impact(UUID.randomUUID(), new RVP_NamedVehicleObbs.Key("top", 0), 1);
        assertNull(RVP_ExplosionHitboxContext.current());
        RVP_ExplosionHitboxContext.run(outer, () -> {
            assertSame(outer, RVP_ExplosionHitboxContext.current());
            assertThrows(IllegalStateException.class, () -> RVP_ExplosionHitboxContext.run(null, () -> {
                assertNull(RVP_ExplosionHitboxContext.current()); throw new IllegalStateException("nested");
            }));
            assertSame(outer, RVP_ExplosionHitboxContext.current());
        });
        assertNull(RVP_ExplosionHitboxContext.current());
    }
}
