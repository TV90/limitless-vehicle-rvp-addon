package org.ywzj.rvp.guidance;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** The gunner's pre-launch lock. Each projectile receives a separate copy at spawn. */
public final class RVP_OpticalLock {
    private record Designation(UUID owner, ResourceLocation weapon, UUID entity, Vec3 ground, float magnification) {}
    public record Result(@Nullable Entity entity, Vec3 point, float confidence) {}
    private static final Map<WeaponUnit, Designation> LOCKS = new WeakHashMap<>();

    private RVP_OpticalLock() {}

    public static Result designate(LivingEntity gunner, WeaponUnit station, RVP_WeaponData data,
                                    @Nullable Entity target, @Nullable Vec3 groundHint, boolean zoomed) {
        WeaponUnit root = station.getRootParentWeaponUnit();
        LOCKS.remove(root);
        var guidance = data.getGuidanceData();
        var view = RVP_OpticalTargeting.gunnerView(root);
        var range = RVP_OpticalTargeting.lockRange(guidance);
        // 识别视场按配置的总角换算成单侧夹角。
        double angle = guidance.getMaxLockHalfAngle();
        float magnification = RVP_OpticalTargeting.scopeMagnification(station, zoomed);
        if (target != null) {
            var lock = validateEntity(gunner, root.getVehicle(), view, data, target, angle, magnification);
            if (lock != null) LOCKS.put(root,
                    new Designation(gunner.getUUID(), data.getWeaponId(), target.getUUID(), null, magnification));
            return lock;
        }
        if (groundHint == null || !RVP_OpticalTargeting.inView(view, groundHint, range, angle)
                || !RVP_OpticalTargeting.groundVisible(gunner, view.origin(), groundHint)) return null;
        // The client provides a hint; require a real block face at that position in the loaded world.
        Vec3 direction = groundHint.subtract(view.origin()).normalize();
        var hit = gunner.level().clip(new ClipContext(groundHint.subtract(direction.scale(0.12)),
                groundHint.add(direction.scale(0.12)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, gunner));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        LOCKS.put(root, new Designation(gunner.getUUID(), data.getWeaponId(), null, hit.getLocation(), magnification));
        return new Result(null, hit.getLocation(), 1f);
    }

    public static Result resolve(LivingEntity gunner, WeaponUnit root, RVP_WeaponData data) {
        Designation lock = LOCKS.get(root);
        if (lock == null || !lock.owner().equals(gunner.getUUID()) || !lock.weapon().equals(data.getWeaponId())
                || !(gunner.level() instanceof ServerLevel level)) return null;
        var view = RVP_OpticalTargeting.gunnerView(root);
        var guidance = data.getGuidanceData();
        var range = RVP_OpticalTargeting.lockRange(guidance);
        if (lock.ground() != null) {
            return RVP_OpticalTargeting.inView(view, lock.ground(), range, 180.0)
                    && RVP_OpticalTargeting.groundVisible(gunner, view.origin(), lock.ground())
                    ? new Result(null, lock.ground(), 1f) : null;
        }
        Entity target = level.getEntity(lock.entity());
        // At fire time the confirmed lock is authoritative. Still check range and visibility.
        return validateEntity(gunner, root.getVehicle(), view, data, target, 180.0, lock.magnification());
    }

    @Nullable
    static Result validateEntity(LivingEntity gunner, org.ywzj.vehicle.entity.vehicle.AbstractVehicle carrier,
                                 RVP_OpticalTargeting.View view, RVP_WeaponData data,
                                 @Nullable Entity target, double angle, float magnification) {
        if (target == null || !RVP_OpticalTargeting.isCandidate(gunner, carrier, target, false)) return null;
        var guidance = data.getGuidanceData();
        var contact = RVP_OpticalTargeting.sample(gunner, view, target, RVP_OpticalTargeting.lockRange(guidance), angle);
        float confidence = RVP_OpticalTargeting.scopeConfidence(contact, view, magnification);
        return confidence >= guidance.getRecognitionLockThreshold()
                ? new Result(target, contact.point(), confidence) : null;
    }

    public static Vec3 groundPoint(LivingEntity gunner, WeaponUnit root, RVP_WeaponData data) {
        Designation lock = LOCKS.get(root);
        return lock != null && lock.owner().equals(gunner.getUUID()) && lock.weapon().equals(data.getWeaponId())
                ? lock.ground() : null;
    }
}
