package org.ywzj.rvp.weapon.core;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

/**
 * {@link AimContext} helpers aligned with {@link org.ywzj.vehicle.vehicle.weapon.VehicleCannon} /
 * {@link org.ywzj.vehicle.vehicle.weapon.VehicleRocket}: {@code from} = muzzle, {@code position} = CCIP / crosshair hit.
 */
public final class RVP_AimContexts {

    private RVP_AimContexts() {}

    /** Muzzle world position; matches {@code aimContext.from} on official weapons. */
    public static Vec3 muzzle(AimContext aim) {
        if (aim.from != null) {
            return aim.from;
        }
        if (aim.position != null) {
            return aim.position;
        }
        return Vec3.ZERO;
    }

    @Nullable
    public static Vec3 impactPoint(AimContext aim) {
        return aim.position;
    }
}
