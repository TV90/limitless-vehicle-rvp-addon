package org.ywzj.rvp.weapon.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.vehicle.api.entity.OBBEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.structure.OBB;

/**
 * OBB impact face normals for RVP (bounce / incidence). Hit selection uses {@link VectorUtil#obbHit}.
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
        VectorUtil.HitOBB hit = VectorUtil.obbHit(obbEntity.getOBBs(), segmentStart, segmentEnd);
        if (hit == null) {
            return null;
        }
        return faceNormalAtHit(hit.obb(), hit.hitPos(), incomingVelocity);
    }

    /** Outward face normal on the struck OBB (faces incoming velocity). */
    private static Vec3 faceNormalAtHit(OBB obb, Vec3 hitWorld, Vec3 incomingVelocity) {
        Vector3f[] axes = obb.getAxes();
        Vector3f local = obb.worldToLocal(hitWorld.toVector3f(), axes);
        Vector3f ext = obb.extents();

        float dx = Math.abs(Math.abs(local.x) - ext.x);
        float dy = Math.abs(Math.abs(local.y) - ext.y);
        float dz = Math.abs(Math.abs(local.z) - ext.z);

        Vector3f axis;
        float sign;
        if (dx <= dy && dx <= dz) {
            axis = axes[0];
            sign = local.x >= 0f ? 1f : -1f;
        } else if (dy <= dz) {
            axis = axes[1];
            sign = local.y >= 0f ? 1f : -1f;
        } else {
            axis = axes[2];
            sign = local.z >= 0f ? 1f : -1f;
        }
        Vec3 normal = new Vec3(axis.x * sign, axis.y * sign, axis.z * sign);
        if (incomingVelocity.lengthSqr() > 1.0E-12 && incomingVelocity.dot(normal) > 0) {
            normal = normal.scale(-1);
        }
        return normal;
    }
}
