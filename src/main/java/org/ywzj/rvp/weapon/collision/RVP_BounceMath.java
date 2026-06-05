package org.ywzj.rvp.weapon.collision;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Incidence-angle ricochet math shared by server and client bullet prediction.
 */
public final class RVP_BounceMath {

    private RVP_BounceMath() {}

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

    /** Outward normal at an entity hull hit (faces incoming velocity). */
    public static Vec3 entityImpactNormal(Entity entity, Vec3 hitLocation, Vec3 incomingVelocity) {
        AABB box = entity.getBoundingBox();
        Vec3 normal = hitLocation.subtract(box.minX + box.getXsize() * 0.5,
                box.minY + box.getYsize() * 0.5,
                box.minZ + box.getZsize() * 0.5);
        if (normal.lengthSqr() < 1.0E-8) {
            normal = incomingVelocity.lengthSqr() > 1.0E-8
                    ? incomingVelocity.normalize().scale(-1)
                    : new Vec3(0, 1, 0);
        } else {
            normal = normal.normalize();
        }
        if (incomingVelocity.dot(normal) > 0) {
            normal = normal.scale(-1);
        }
        return normal;
    }
}
