package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One tick steering intent produced by a guidance source before compositing.
 */
public record RVP_GuidanceIntent(
        boolean success,
        @Nullable Vec3 aimPoint,
        @Nullable Entity aimEntity,
        boolean directMotion,
        double weight,
        RVP_EnumGuidanceType sourceType
) {
    public static RVP_GuidanceIntent failed(RVP_EnumGuidanceType type) {
        return new RVP_GuidanceIntent(false, null, null, false, 0.0, type);
    }

    public static RVP_GuidanceIntent point(Vec3 point, boolean direct, double weight, RVP_EnumGuidanceType type) {
        return new RVP_GuidanceIntent(true, point, null, direct, weight, type);
    }

    public static RVP_GuidanceIntent entity(Entity entity, boolean direct, double weight, RVP_EnumGuidanceType type) {
        return new RVP_GuidanceIntent(true, null, entity, direct, weight, type);
    }

    @Nullable
    public Vec3 directionFrom(Vec3 origin) {
        if (aimPoint != null) {
            Vec3 delta = aimPoint.subtract(origin);
            return delta.lengthSqr() > 1.0E-6 ? delta.normalize() : null;
        }
        if (aimEntity != null && aimEntity.isAlive()) {
            Vec3 delta = aimEntity.getBoundingBox().getCenter().subtract(origin);
            return delta.lengthSqr() > 1.0E-6 ? delta.normalize() : null;
        }
        return null;
    }
}
