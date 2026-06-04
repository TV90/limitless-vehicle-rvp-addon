package org.ywzj.rvp.weapon.laser;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Shared laser geometry: muzzle clip-out + ray trace to first block/entity hit.
 */
public final class RVP_LaserRaycast {

    private static final double CLIP_STEP = 0.08;
    private static final double MAX_MUZZLE_CLIP = 3.0;
    private static final double MIN_RENDER_START = 0.5;
    private static final double SURFACE_INSET = 0.05;

    private RVP_LaserRaycast() {}

    public static RVP_LaserBeam computeBeam(Level level, AbstractVehicle vehicle, @Nullable LivingEntity shooter,
                                           Vec3 muzzle, Vec3 lookDirection, float maxRange,
                                           double configuredRenderStart) {
        Vec3 look = normalize(lookDirection);
        double minStart = Math.max(configuredRenderStart, MIN_RENDER_START);
        Vec3 renderStart = clipMuzzleExit(level, vehicle, muzzle, look, minStart);
        TraceResult trace = traceFrom(level, vehicle, shooter, renderStart, look, maxRange);

        Vec3 impactPoint = trace.hitEnd;
        Vec3 renderEnd = impactPoint;
        if (trace.hitSomething) {
            renderEnd = impactPoint.subtract(look.scale(SURFACE_INSET));
        }

        return new RVP_LaserBeam(muzzle, renderStart, renderEnd, impactPoint,
                trace.hitSomething, trace.hitEntity, trace.blockHit);
    }

    private static Vec3 clipMuzzleExit(Level level, AbstractVehicle vehicle, Vec3 muzzle, Vec3 look, double minDistance) {
        AABB vehicleBox = vehicle.getBoundingBox().inflate(0.25);
        Vec3 fallback = muzzle.add(look.scale(minDistance));

        for (double dist = minDistance; dist <= MAX_MUZZLE_CLIP; dist += CLIP_STEP) {
            Vec3 point = muzzle.add(look.scale(dist));
            if (vehicleBox.contains(point)) {
                continue;
            }
            Vec3 probeEnd = point.add(look.scale(CLIP_STEP * 0.5));
            BlockHitResult blockHit = level.clip(new ClipContext(point, probeEnd,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
            if (blockHit.getType() != HitResult.Type.MISS) {
                continue;
            }
            return point;
        }
        return fallback;
    }

    private static TraceResult traceFrom(Level level, AbstractVehicle vehicle, @Nullable LivingEntity shooter,
                                       Vec3 start, Vec3 look, float maxRange) {
        Vec3 end = start.add(look.scale(maxRange));

        BlockHitResult blockHit = level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        double bestDist = maxRange;
        Vec3 hitEnd = end;
        BlockHitResult blockResult = null;

        if (blockHit.getType() != HitResult.Type.MISS) {
            bestDist = blockHit.getLocation().distanceTo(start);
            hitEnd = blockHit.getLocation();
            blockResult = blockHit;
        }

        Entity hitEntity = null;
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                level,
                shooter,
                start,
                hitEnd,
                vehicle.getBoundingBox().expandTowards(look.scale(bestDist)).inflate(1.0),
                e -> canHit(e, vehicle, shooter));
        if (entityHit != null) {
            double dist = start.distanceTo(entityHit.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                hitEnd = entityHit.getLocation();
                hitEntity = entityHit.getEntity();
                blockResult = null;
            }
        }

        boolean hit = hitEntity != null || blockHit.getType() != HitResult.Type.MISS;
        return new TraceResult(hitEnd, hit, hitEntity, blockResult);
    }

    private static Vec3 normalize(Vec3 direction) {
        return direction.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : direction.normalize();
    }

    private static boolean canHit(Entity entity, AbstractVehicle vehicle, @Nullable LivingEntity shooter) {
        if (!entity.isAlive() || !entity.isPickable()) {
            return false;
        }
        if (entity == vehicle || entity == shooter) {
            return false;
        }
        return !vehicle.getPassengers().contains(entity);
    }

    private record TraceResult(Vec3 hitEnd, boolean hitSomething, @Nullable Entity hitEntity,
                               @Nullable BlockHitResult blockHit) {}
}
