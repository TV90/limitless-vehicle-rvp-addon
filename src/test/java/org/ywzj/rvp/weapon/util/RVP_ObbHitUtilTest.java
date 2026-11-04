package org.ywzj.rvp.weapon.util;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.vehicle.structure.OBB;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RVP_ObbHitUtilTest {
    @Test
    void onlyLocalTopQualifiesEvenWhenTheBoxIsTilted() {
        var rotation = new Quaternionf().rotateZ((float) (Math.PI / 2));
        var center = new Vector3f(30, 7, -12);
        var obb = new OBB(center, new Vector3f(2, 1, 3), rotation);
        for (Direction face : Direction.values()) {
            var normal = face.getNormal();
            var start = new Vector3f(normal.getX() * 6, normal.getY() * 6, normal.getZ() * 6);
            rotation.transform(start).add(center);
            var impact = new Vec3(obb.clip(start, center).orElseThrow());
            assertEquals(face == Direction.UP, RVP_ObbHitUtil.isTopFace(obb, impact), face.name());
        }
    }

    @Test
    void aRayThatStartsInsideTheBoxHasNoTopFaceContact() {
        var obb = new OBB(new Vector3f(), new Vector3f(2, 1, 3), new Quaternionf());
        var hit = obb.clip(new Vector3f(0, .8f, 0), new Vector3f(0, -4, 0)).orElseThrow();
        assertFalse(RVP_ObbHitUtil.isTopFace(obb, new Vec3(hit)));
    }
}
