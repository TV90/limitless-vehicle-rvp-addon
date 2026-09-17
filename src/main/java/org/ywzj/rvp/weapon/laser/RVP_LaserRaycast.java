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
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.List;
import java.util.Optional;

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
                trace.hitSomething, trace.hitEntity, trace.blockHit, trace.smokeBlocked);
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
            // 原版 ProjectileUtil.getEntityHitResult 返回的 EntityHitResult 位置是
            // entity.position()（实体脚底坐标），直接用作落点会让光束末端折向生物脚底
            // （2026-09-18 实机反馈）。对命中实体碰撞箱重做射线求交取真实入射点
            // （0 膨胀，与 ProjectileUtil 内部几何一致），此处 hitEnd 尚为方块命中点/
            // 射程端点，即实体搜索段的终点，clip 失败时才回退脚底坐标。
            Vec3 entityHitPos = entityHit.getEntity().getBoundingBox().clip(start, hitEnd)
                    .orElse(entityHit.getEntity().position());
            double dist = start.distanceTo(entityHitPos);
            if (dist < bestDist) {
                bestDist = dist;
                hitEnd = entityHitPos;
                hitEntity = entityHit.getEntity();
                blockResult = null;
            }
        }

        boolean smokeBlocked = false;
        // 烟雾弹反制激光压制（2026-09-18 用户定版）：激光射线命中 RVP 烟幕实体 AABB 即提前判命中并截断，
        // 光束止于云团表面。hitEntity 保持 null → 服务端伤害/告警/致盲随实体命中门控自动跳过；
        // 不分敌我（烟挡光的物理语义）；飞行段小箱（0.5³）与爆炸后云团箱统一 AABB 判据。
        // 查询范围仅需射线走廊盒，烟幕实体数量少，量级与制导链 SightObstruction 查询相当。
        List<RVP_SmokeEntity> smokes = level.getEntitiesOfClass(RVP_SmokeEntity.class,
                new AABB(start, hitEnd), Entity::isAlive);
        for (RVP_SmokeEntity smoke : smokes) {
            Optional<Vec3> smokeHit = smoke.getBoundingBox().clip(start, hitEnd);
            if (smokeHit.isEmpty()) {
                continue;
            }
            double dist = start.distanceTo(smokeHit.get());
            if (dist < bestDist) {
                bestDist = dist;
                hitEnd = smokeHit.get();
                hitEntity = null;
                blockResult = null;
                smokeBlocked = true;
            }
        }

        boolean hit = hitEntity != null || smokeBlocked || blockHit.getType() != HitResult.Type.MISS;
        return new TraceResult(hitEnd, hit, hitEntity, blockResult, smokeBlocked);
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
                               @Nullable BlockHitResult blockHit, boolean smokeBlocked) {}
}
