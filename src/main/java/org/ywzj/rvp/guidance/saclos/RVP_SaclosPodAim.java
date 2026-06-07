package org.ywzj.rvp.guidance.saclos;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Cockpit SACLOS designation from the vehicle sight pod, honoring body-mod focus lock and EO track.
 */
public final class RVP_SaclosPodAim {

    private RVP_SaclosPodAim() {}

    @Nullable
    public static Vec3 resolvePodAimPoint(@Nullable WeaponUnit unit) {
        if (unit == null) {
            return null;
        }
        if (unit.withFocusLocker()) {
            Vec3 focus = unit.getFocusLockPos();
            if (focus != null) {
                return focus;
            }
        }
        Entity locked = unit.getLockedEntity();
        if (locked != null && locked.isAlive()) {
            return locked.getBoundingBox().getCenter();
        }
        return unit.aimHitPosition();
    }
}
