package org.ywzj.rvp.weapon.util;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.api.entity.OBBEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Incidence-angle ricochet math shared by server and client bullet prediction.
 */
public final class RVP_BounceUtil {

    private RVP_BounceUtil() {}

    /**
     * Angle between velocity and surface normal (degrees). 0° = perpendicular hit, 90° = grazing.
     */
    public static float incidenceAngleDegrees(Vec3 velocity, Vec3 surfaceNormal) {
        if (velocity.lengthSqr() < 1.0E-12 || surfaceNormal.lengthSqr() < 1.0E-12) {
            return 0f;
        }
        double dot = Math.abs(velocity.normalize().dot(surfaceNormal.normalize()));
        return (float) Math.toDegrees(Math.acos(Mth.clamp(dot, 0.0, 1.0)));
    }

    /**
     * @param minIncidenceDeg configured threshold; 0 = no angle gate.
     */
    public static boolean meetsIncidenceThreshold(Vec3 velocity, Vec3 surfaceNormal, float minIncidenceDeg) {
        if (minIncidenceDeg <= 0f) {
            return true;
        }
        return incidenceAngleDegrees(velocity, surfaceNormal) >= minIncidenceDeg;
    }

    public static Vec3 reflect(Vec3 velocity, Vec3 surfaceNormal, float bounceStrength) {
        Vec3 normal = surfaceNormal.normalize();
        return velocity.subtract(normal.scale(2.0 * velocity.dot(normal))).scale(bounceStrength);
    }

    /** Outward normal at an entity hull hit (faces incoming velocity). Prefer {@link #impactNormal} when the tick segment is known. */
    public static Vec3 entityImpactNormal(Entity entity, Vec3 hitLocation, Vec3 incomingVelocity) {
        return entitySurfaceNormal(entity, hitLocation, incomingVelocity);
    }

    /**
     * Surface normal for entity hits. {@link AbstractVehicle} uses OBB ray hit (same as
     * {@link org.ywzj.vehicle.util.EntityUtil#getHitResult}); others use AABB face.
     */
    public static Vec3 impactNormal(Entity entity, Vec3 segmentStart, Vec3 segmentEnd,
                                    Vec3 hitLocation, Vec3 incomingVelocity) {
        if (entity instanceof OBBEntity) {
            Vec3 obbNormal = RVP_ObbHitUtil.obbImpactNormal(entity, segmentStart, segmentEnd, incomingVelocity);
            if (obbNormal != null) {
                return obbNormal;
            }
        }
        return entitySurfaceNormal(entity, hitLocation, incomingVelocity);
    }

    /**
     * Closest AABB face normal at {@code hitLocation}. Grazing hits get high incidence angles;
     * center-to-hit vectors underestimate angle on large vehicles.
     */
    public static Vec3 entitySurfaceNormal(Entity entity, Vec3 hitLocation, Vec3 incomingVelocity) {
        AABB box = entity.getBoundingBox();
        double cx = (box.minX + box.maxX) * 0.5;
        double cy = (box.minY + box.maxY) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double halfX = Math.max(box.getXsize() * 0.5, 1.0E-4);
        double halfY = Math.max(box.getYsize() * 0.5, 1.0E-4);
        double halfZ = Math.max(box.getZsize() * 0.5, 1.0E-4);
        double relX = (hitLocation.x - cx) / halfX;
        double relY = (hitLocation.y - cy) / halfY;
        double relZ = (hitLocation.z - cz) / halfZ;
        Vec3 normal;
        if (relX >= relY && relX >= relZ) {
            normal = new Vec3(Math.signum(relX), 0, 0);
        } else if (relY >= relZ) {
            normal = new Vec3(0, Math.signum(relY), 0);
        } else {
            normal = new Vec3(0, 0, Math.signum(relZ));
        }
        if (normal.lengthSqr() < 1.0E-8) {
            normal = incomingVelocity.lengthSqr() > 1.0E-8
                    ? incomingVelocity.normalize().scale(-1)
                    : new Vec3(0, 1, 0);
        }
        if (incomingVelocity.dot(normal) > 0) {
            normal = normal.scale(-1);
        }
        return normal;
    }
}
