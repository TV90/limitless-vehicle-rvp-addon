package org.ywzj.rvp.weapon.util;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.vehicle.api.entity.OBBEntity;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.List;

/**
 * OBB impact face normals for RVP (bounce / incidence).
 */
public final class RVP_ObbHitUtil {

    private RVP_ObbHitUtil() {}

    /**
     * Outward OBB face normal at the ray hit (faces incoming velocity), or {@code null} if no OBB hit.
     */
    public static Vec3 obbImpactNormal(Entity entity, Vec3 segmentStart, Vec3 segmentEnd, Vec3 incomingVelocity) {
        if (!(entity instanceof OBBEntity obbEntity)) {
            return null;
        }
        List<OBB> obbs = obbEntity.getOBBs();
        if (obbs == null || obbs.isEmpty()) {
            return null;
        }
        HitOBB hit = hitOBB(obbs, segmentStart, segmentEnd);
        if (hit == null) {
            return null;
        }
        return faceNormalAtHit(hit.obb(), hit.hitPos(), incomingVelocity);
    }

    private record HitOBB(OBB obb, Vec3 hitPos, double distance) {}

    private static HitOBB hitOBB(List<OBB> obbs, Vec3 start, Vec3 end) {
        double minDistance = Double.MAX_VALUE;
        Vec3 minDistanceHitPos = null;
        OBB minDistanceOBB = null;
        Vector3f from = start.toVector3f();
        Vector3f to = end.toVector3f();
        for (OBB obb : obbs) {
            Vector3f hit = obb.clip(from, to).orElse(null);
            if (hit == null) {
                continue;
            }
            Vec3 hitPos = new Vec3(hit);
            double distance = hitPos.distanceTo(start);
            if (distance < minDistance) {
                minDistance = distance;
                minDistanceHitPos = hitPos;
                minDistanceOBB = obb;
            }
        }
        return minDistanceOBB == null ? null : new HitOBB(minDistanceOBB, minDistanceHitPos, minDistance);
    }

    /** Outward face normal on the struck OBB (faces incoming velocity). */
    public static Vec3 faceNormalAtHit(OBB obb, Vec3 hitWorld, Vec3 incomingVelocity) {
        Vector3f[] axes = obb.getAxes();
        Vector3f local = obb.worldToLocal(hitWorld.toVector3f(), axes);
        Direction face = localFace(local, obb.extents());
        Vector3f axis = axes[switch (face.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        }];
        Vec3 normal = new Vec3(axis).scale(face.getAxisDirection().getStep());
        if (incomingVelocity.lengthSqr() > 1.0E-12 && incomingVelocity.dot(normal) > 0) {
            normal = normal.scale(-1);
        }
        return normal;
    }

    /** The struck box's local +Y face, regardless of the box's world orientation. */
    public static boolean isTopFace(OBB obb, Vec3 hitWorld) {
        Vector3f local = obb.worldToLocal(hitWorld.toVector3f(), obb.getAxes());
        // 本体的 clip 在弹体已位于盒内时会返回起点，此时没有实际的入射面。
        float tolerance = Math.max(1.0e-4f, 2f * Math.max(
                Math.ulp((float) hitWorld.x), Math.max(Math.ulp((float) hitWorld.y), Math.ulp((float) hitWorld.z))));
        return Math.abs(local.y - obb.extents().y) <= tolerance && localFace(local, obb.extents()) == Direction.UP;
    }

    private static Direction localFace(Vector3f local, Vector3f ext) {
        float dx = Math.abs(Math.abs(local.x) - ext.x);
        float dy = Math.abs(Math.abs(local.y) - ext.y);
        float dz = Math.abs(Math.abs(local.z) - ext.z);

        if (dx <= dy && dx <= dz) {
            return local.x >= 0f ? Direction.EAST : Direction.WEST;
        }
        if (dy <= dz) {
            return local.y >= 0f ? Direction.UP : Direction.DOWN;
        }
        return local.z >= 0f ? Direction.SOUTH : Direction.NORTH;
    }
}
