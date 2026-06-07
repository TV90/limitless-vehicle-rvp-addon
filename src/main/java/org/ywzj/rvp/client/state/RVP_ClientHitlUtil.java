package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.util.VectorUtil;

public final class RVP_ClientHitlUtil {

    private static final double NOSE_OFFSET = 0.35;

    private RVP_ClientHitlUtil() {}

    public static Vec3 lensPosition(Entity missile, float bodyYaw, float bodyPitch) {
        if (RVP_ClientHitlCamera.hasPose()) {
            return RVP_ClientHitlCamera.getSmoothedLensPosition();
        }
        if (missile == null) {
            return Vec3.ZERO;
        }
        Vec3 noseOffset = VectorUtil.rotToVec(bodyPitch, bodyYaw).normalize().scale(NOSE_OFFSET);
        return missile.position().add(noseOffset);
    }

    public static HitResult raycastFromMissileView(Minecraft mc, Entity missile, float yaw, float pitch, double range) {
        return raycastFromMissileView(mc, missile, yaw, pitch, range, true);
    }

    /** Aim point on terrain/entity, or a virtual point along the line of sight (sky). */
    @Nullable
    public static Vec3 resolveAimPoint(
            Minecraft mc, Entity missile, float aimYaw, float aimPitch, double range, boolean includeEntities) {
        HitResult hit = raycastFromBodyAim(mc, missile, aimYaw, aimPitch, range, includeEntities);
        if (hit != null) {
            if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit
                    && entityHit.getEntity() != null) {
                return entityHit.getEntity().getBoundingBox().getCenter();
            }
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getLocation();
            }
        }
        return aimPointAlongRay(missile, aimYaw, aimPitch, range);
    }

    public static Vec3 aimPointAlongRay(Entity missile, float aimYaw, float aimPitch, double range) {
        float bodyYaw = RVP_ClientHitlCamera.getBodyYaw();
        float bodyPitch = RVP_ClientHitlCamera.getBodyPitch();
        Vec3 start = lensPosition(missile, bodyYaw, bodyPitch);
        if (start.lengthSqr() <= 1.0E-4) {
            start = missile.position().add(VectorUtil.rotToVec(bodyPitch, bodyYaw).normalize().scale(1.2));
        }
        return start.add(VectorUtil.rotToVec(aimPitch, aimYaw).normalize().scale(range));
    }

    /** Ray from body-fixed lens along aim direction (body + offset). */
    public static HitResult raycastFromBodyAim(
            Minecraft mc, Entity missile, float aimYaw, float aimPitch, double range, boolean includeEntities) {
        if (mc.level == null || missile == null) {
            return null;
        }
        float bodyYaw = RVP_ClientHitlCamera.getBodyYaw();
        float bodyPitch = RVP_ClientHitlCamera.getBodyPitch();
        Vec3 start = lensPosition(missile, bodyYaw, bodyPitch);
        if (start.lengthSqr() <= 1.0E-4) {
            start = missile.position().add(VectorUtil.rotToVec(bodyPitch, bodyYaw).normalize().scale(1.2));
        }
        Vec3 end = start.add(VectorUtil.rotToVec(aimPitch, aimYaw).normalize().scale(range));
        BlockHitResult blockHit = mc.level.clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, missile));
        if (!includeEntities) {
            return blockHit;
        }
        EntityHitResult entityHit = findEntityHit(mc, missile, start, end);
        if (entityHit != null && (blockHit.getType() == HitResult.Type.MISS
                || entityHit.getLocation().distanceToSqr(start) < blockHit.getLocation().distanceToSqr(start))) {
            return entityHit;
        }
        return blockHit;
    }

    /** HUD aim: block clip only (no per-particle entity scan). Designate mode passes {@code includeEntities=true}. */
    public static HitResult raycastFromMissileView(
            Minecraft mc, Entity missile, float yaw, float pitch, double range, boolean includeEntities) {
        if (RVP_ClientHitlState.isDesignateMode()) {
            return raycastFromBodyAim(mc, missile, yaw, pitch, range, includeEntities);
        }
        if (mc.level == null || missile == null) {
            return null;
        }
        Vec3 start = lensPosition(missile, yaw, pitch);
        if (start.lengthSqr() <= 1.0E-4) {
            start = missile.position().add(VectorUtil.rotToVec(pitch, yaw).normalize().scale(1.2));
        }
        Vec3 end = start.add(VectorUtil.rotToVec(pitch, yaw).normalize().scale(range));
        BlockHitResult blockHit = mc.level.clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, missile));
        if (!includeEntities) {
            return blockHit;
        }
        EntityHitResult entityHit = findEntityHit(mc, missile, start, end);
        if (entityHit != null && (blockHit.getType() == HitResult.Type.MISS
                || entityHit.getLocation().distanceToSqr(start) < blockHit.getLocation().distanceToSqr(start))) {
            return entityHit;
        }
        return blockHit;
    }

    private static EntityHitResult findEntityHit(Minecraft mc, Entity missile, Vec3 start, Vec3 end) {
        EntityHitResult closest = null;
        double closestDist = Double.MAX_VALUE;
        Entity owner = missile instanceof RVP_BaseBullet bullet ? bullet.getOwner() : null;
        for (Entity entity : mc.level.getEntities(missile, missile.getBoundingBox().expandTowards(end.subtract(start)).inflate(1.0),
                e -> e.isPickable() && e != missile && (owner == null || !e.is(owner)))) {
            var box = entity.getBoundingBox().inflate(0.3);
            var hit = box.clip(start, end);
            if (hit.isPresent()) {
                double dist = start.distanceToSqr(hit.get());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = new EntityHitResult(entity, hit.get());
                }
            }
        }
        return closest;
    }
}
