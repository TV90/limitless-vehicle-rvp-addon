package org.ywzj.rvp.weapon.laser;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;
import org.ywzj.rvp.util.RVP_ServerVehicleIndex;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Predicate;

/**
 * Shared laser geometry: muzzle clip-out + ray trace to first block/entity hit.
 */
public final class RVP_LaserRaycast {

    /** 光束起点尝试离开发射载具包围盒的采样步长。 */
    private static final double CLIP_STEP = 0.08;
    /** 只限制视觉避让的距离，不改变命中判定。 */
    private static final double MAX_MUZZLE_CLIP = 3.0;
    /** 不把光束模型画进发射装置内部。 */
    private static final double MIN_RENDER_START = 0.5;
    /** 光束模型离命中表面留少量间隙，真实落点保持原坐标。 */
    private static final double SURFACE_INSET = 0.05;

    private RVP_LaserRaycast() {}

    public static RVP_LaserBeam computeBeam(Level level, AbstractVehicle vehicle, @Nullable LivingEntity shooter,
                                           Vec3 muzzle, Vec3 lookDirection, float maxRange,
                                           double configuredRenderStart) {
        Vec3 look = normalize(lookDirection);
        if (look.lengthSqr() == 0 || !Float.isFinite(maxRange) || maxRange <= 0f) {
            return new RVP_LaserBeam(muzzle, muzzle, muzzle, muzzle, false, null, null, false, null);
        }
        // 命中射段始终从炮口开始，贴脸遮挡不能被光束模型的起始偏移跳过。
        TraceResult trace = traceFrom(level, vehicle, shooter, muzzle, look, maxRange);
        double renderedDistance = Math.max(0.0, muzzle.distanceTo(trace.hitEnd)
                - (trace.hitSomething ? SURFACE_INSET : 0.0));
        double minStart = Double.isFinite(configuredRenderStart)
                ? Math.max(configuredRenderStart, MIN_RENDER_START) : MIN_RENDER_START;
        double renderStartDistance = Math.min(renderedDistance, clipMuzzleExit(vehicle, muzzle, look, minStart));
        Vec3 renderStart = muzzle.add(look.scale(renderStartDistance));
        Vec3 renderEnd = muzzle.add(look.scale(renderedDistance));
        return new RVP_LaserBeam(muzzle, renderStart, renderEnd, trace.hitEnd,
                trace.hitSomething, trace.hitEntity, trace.blockHit, trace.smokeBlocked, trace.vehicleHit);
    }

    private static double clipMuzzleExit(AbstractVehicle vehicle, Vec3 muzzle, Vec3 look, double minDistance) {
        AABB vehicleBox = vehicle.getBoundingBox().inflate(0.25);
        for (double dist = minDistance; dist <= MAX_MUZZLE_CLIP; dist += CLIP_STEP) {
            if (!vehicleBox.contains(muzzle.add(look.scale(dist)))) return dist;
        }
        return minDistance;
    }

    private static TraceResult traceFrom(Level level, AbstractVehicle vehicle, @Nullable LivingEntity shooter,
                                       Vec3 start, Vec3 look, float maxRange) {
        Vec3 end = start.add(look.scale(maxRange));

        BlockHitResult blockHit = level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        BlockHitResult blockResult = blockHit.getType() == HitResult.Type.BLOCK ? blockHit : null;
        Vec3 hitEnd = blockResult == null ? end : blockResult.getLocation();
        double bestDist = start.distanceToSqr(hitEnd);

        AABB search = new AABB(start, hitEnd).inflate(1.0E-4);
        Predicate<Entity> filter = entity -> entity instanceof RVP_SmokeEntity
                ? entity.isAlive() : canHit(entity, vehicle, shooter);
        // 与弹体共用服务端载具索引，补足大型载具的原版分节查询盲区。
        var localTargets = level.getEntities(vehicle, search, filter);
        var targets = level.isClientSide() ? localTargets
                : RVP_ServerVehicleIndex.mergeWithVehicleIndex(level, search, localTargets, filter);
        boolean smokeBlocked = false;
        // 优先裁剪烟幕，炮口在烟幕内部时光束也会立即被挡住。
        for (Entity target : targets) {
            if (!(target instanceof RVP_SmokeEntity smoke)) continue;
            Vec3 smokeHit = clipBox(smoke.getBoundingBox(), start, hitEnd);
            if (smokeHit == null) continue;
            double dist = start.distanceToSqr(smokeHit);
            if (dist <= bestDist) {
                bestDist = dist;
                hitEnd = smokeHit;
                blockResult = null;
                smokeBlocked = true;
            }
        }

        Entity hitEntity = null;
        RVP_NamedVehicleObbs.Hit vehicleHit = null;
        if (bestDist > 0.0) {
            Vec3 limit = hitEnd;
            for (Entity target : targets) {
                if (target instanceof RVP_SmokeEntity) continue;
                RVP_NamedVehicleObbs.Hit namedHit = null;
                Vec3 point;
                if (target instanceof AbstractVehicle other) {
                    // 获取实时炮塔等部件的受击盒，且排除纯物理盒及已消耗的爆反。
                    var boxes = RVP_NamedVehicleObbs.collect(other);
                    if (boxes.isEmpty()) {
                        point = RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(other, start, limit);
                    } else {
                        namedHit = RVP_NamedVehicleObbs.raycast(boxes, start, limit);
                        point = namedHit == null ? null : namedHit.point();
                    }
                } else {
                    point = clipBox(target.getBoundingBox(), start, limit);
                }
                if (point == null) continue;
                double dist = start.distanceToSqr(point);
                if (dist < bestDist) {
                    bestDist = dist;
                    hitEnd = point;
                    hitEntity = target;
                    vehicleHit = namedHit;
                    blockResult = null;
                    smokeBlocked = false;
                }
            }
        }
        return new TraceResult(hitEnd, hitEntity != null || smokeBlocked || blockResult != null,
                hitEntity, blockResult, smokeBlocked, vehicleHit);
    }

    @Nullable
    private static Vec3 clipBox(AABB box, Vec3 start, Vec3 end) {
        return box.contains(start) ? start : box.clip(start, end).orElse(null);
    }

    private static Vec3 normalize(Vec3 direction) {
        double lengthSquared = direction.lengthSqr();
        return Double.isFinite(lengthSquared) && lengthSquared >= 1.0E-8 ? direction.normalize() : Vec3.ZERO;
    }

    private static boolean canHit(Entity entity, AbstractVehicle vehicle, @Nullable LivingEntity shooter) {
        return entity.isAlive() && entity.isPickable() && !entity.isSpectator() && entity != shooter
                && !(entity instanceof PartEntity<?>) && entity.getRootVehicle() != vehicle.getRootVehicle();
    }

    private record TraceResult(Vec3 hitEnd, boolean hitSomething, @Nullable Entity hitEntity,
                               @Nullable BlockHitResult blockHit, boolean smokeBlocked,
                               @Nullable RVP_NamedVehicleObbs.Hit vehicleHit) {}
}
