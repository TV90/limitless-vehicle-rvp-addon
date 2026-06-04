package org.ywzj.rvp.countermeasure;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_SeekerData;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.api.entity.TargetObstruction;
import org.ywzj.vehicle.entity.weapon.ActiveProtectionGrenadeEntity;

import java.util.Comparator;
import java.util.Optional;

/**
 * Read-only view of target countermeasures for seeker and guidance decisions.
 *
 * <p>Concrete vehicle skills can later feed this service from part units or
 * capabilities. Keeping the query centralized prevents guidance sources from
 * depending on specific aircraft/tank classes.</p>
 */
public final class RVP_CountermeasureState {

    private RVP_CountermeasureState() {}

    public static Result query(Entity seeker, Entity target, RVP_EnumGuidanceType guidanceType, RVP_SeekerData seekerData) {
        if (seeker != null && hasInterceptorNear(seeker, 8.0)) {
            return new Result(false, false, false, true);
        }
        if (target == null || seekerData == null) {
            return Result.CLEAR;
        }
        if (guidanceType == RVP_EnumGuidanceType.SACLOS || guidanceType == RVP_EnumGuidanceType.TV || guidanceType == RVP_EnumGuidanceType.IR) {
            if (hasSightObstruction(seeker, target)) {
                return new Result(true, true, false, false);
            }
        }
        if ((guidanceType == RVP_EnumGuidanceType.IR && !seekerData.isIgnoreFlares())
                || ((guidanceType == RVP_EnumGuidanceType.ARH || guidanceType == RVP_EnumGuidanceType.SARH) && !seekerData.isIgnoreChaff())) {
            if (hasTargetObstructionNear(target, 16.0)) {
                return new Result(false, true, true, false);
            }
        }
        return Result.CLEAR;
    }

    public static Result queryPoint(Entity seeker, Vec3 targetPos, RVP_EnumGuidanceType guidanceType, RVP_SeekerData seekerData) {
        if (seeker != null && hasInterceptorNear(seeker, 8.0)) {
            return new Result(false, false, false, true);
        }
        if (seeker == null || targetPos == null || seekerData == null) {
            return Result.CLEAR;
        }
        if (guidanceType == RVP_EnumGuidanceType.SACLOS || guidanceType == RVP_EnumGuidanceType.TV || guidanceType == RVP_EnumGuidanceType.IR) {
            if (hasSightObstruction(seeker, targetPos)) {
                return new Result(true, true, false, false);
            }
        }
        return Result.CLEAR;
    }

    public static Optional<Entity> findDecoyTarget(Entity target, double radius) {
        if (target == null) {
            return Optional.empty();
        }
        AABB box = target.getBoundingBox().inflate(radius);
        return target.level().getEntities(target, box, entity -> entity instanceof TargetObstruction).stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(target)));
    }

    private static boolean hasSightObstruction(Entity seeker, Entity target) {
        if (target instanceof SightObstruction) {
            return true;
        }
        if (seeker.level().clip(new ClipContext(seeker.position(), target.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, seeker)).getType() != HitResult.Type.MISS) {
            return true;
        }
        AABB box = new AABB(seeker.position(), target.position()).inflate(2.0);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof SightObstruction).stream()
                .anyMatch(entity -> entity.distanceTo(target) < 12.0);
    }

    private static boolean hasSightObstruction(Entity seeker, Vec3 targetPos) {
        if (seeker.level().clip(new ClipContext(seeker.position(), targetPos,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, seeker)).getType() != HitResult.Type.MISS) {
            return true;
        }
        AABB box = new AABB(seeker.position(), targetPos).inflate(2.0);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof SightObstruction).stream()
                .anyMatch(entity -> distanceToSegment(entity.position(), seeker.position(), targetPos) < 12.0);
    }

    private static boolean hasTargetObstructionNear(Entity target, double radius) {
        AABB box = target.getBoundingBox().inflate(radius);
        return target.level().getEntities(target, box, entity -> entity instanceof TargetObstruction).stream()
                .anyMatch(entity -> entity.distanceTo(target) < radius);
    }

    private static boolean hasInterceptorNear(Entity seeker, double radius) {
        AABB box = seeker.getBoundingBox().inflate(radius);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof ActiveProtectionGrenadeEntity).stream()
                .anyMatch(entity -> entity.distanceTo(seeker) < radius);
    }

    private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lenSqr = ab.lengthSqr();
        if (lenSqr <= 1.0E-6) {
            return point.distanceTo(a);
        }
        double t = Math.max(0.0, Math.min(1.0, point.subtract(a).dot(ab) / lenSqr));
        return point.distanceTo(a.add(ab.scale(t)));
    }

    public record Result(boolean lockBlocked, boolean jammedInFlight, boolean decoyed, boolean intercepted) {
        public static final Result CLEAR = new Result(false, false, false, false);

        public boolean isDenied() {
            return lockBlocked || jammedInFlight || decoyed || intercepted;
        }
    }
}
