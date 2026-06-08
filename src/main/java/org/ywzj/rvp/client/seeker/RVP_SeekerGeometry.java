package org.ywzj.rvp.client.seeker;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.control.InputHandler;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Seeker FOV geometry aligned with vanilla {@link org.ywzj.vehicle.vehicle.weapon.seeker.Infrared}
 * ({@link WeaponUnit#worldPivotPosition()}, {@link WeaponUnit#worldVec()}).
 *
 * <p>Free-camera look is intentionally ignored so IR/SARH locks stay on the weapon-station bore.</p>
 */
public final class RVP_SeekerGeometry {

    private RVP_SeekerGeometry() {}

    /**
     * JSON / vanilla seeker {@code fov} is a half-angle in degrees (±fov from boresight).
     */
    public static float screenFovRadius(@Nullable WeaponUnit operatorUnit, float seekerHalfFovDeg) {
        if (operatorUnit != null) {
            Vec2 rot = operatorUnit.worldRot();
            Vec3 pivot = operatorUnit.worldPivotPosition();
            Vec3 screenUp = VectorUtil.worldToScreen(pivot.add(
                    VectorUtil.rotToVec(rot.x - seekerHalfFovDeg, rot.y).normalize().scale(256)));
            Vec3 screenDown = VectorUtil.worldToScreen(pivot.add(
                    VectorUtil.rotToVec(rot.x + seekerHalfFovDeg, rot.y).normalize().scale(256)));
            if (screenUp.z >= 0 && screenDown.z >= 0) {
                double dx = screenDown.x - screenUp.x;
                double dy = screenDown.y - screenUp.y;
                return (float) Math.sqrt(dx * dx + dy * dy) * 0.5f;
            }
        }
        return Math.max(seekerHalfFovDeg * 1.6f, 4f);
    }

    public static Vec3 aimOrigin(@Nullable WeaponUnit operatorUnit) {
        if (operatorUnit != null) {
            return operatorUnit.worldPivotPosition();
        }
        return Vec3.ZERO;
    }

    /** Weapon-station boresight; matches vanilla {@code Infrared.checkTarget}. */
    public static Vec3 aimDirection(@Nullable WeaponUnit operatorUnit) {
        if (operatorUnit != null) {
            Vec3 bore = operatorUnit.worldVec();
            if (bore.lengthSqr() >= 1.0E-6) {
                return bore.normalize();
            }
        }
        return new Vec3(0, 0, 1);
    }

    /**
     * HUD / candidate-sort reference on screen. Free camera: project weapon bore; otherwise screen centre.
     */
    public static double[] screenReference(@Nullable WeaponUnit operatorUnit, double fallbackX, double fallbackY) {
        if (!InputHandler.freeCamera || operatorUnit == null) {
            return new double[]{fallbackX, fallbackY};
        }
        return boresightScreen(operatorUnit, fallbackX, fallbackY);
    }

    public static double[] boresightScreen(WeaponUnit operatorUnit, double fallbackX, double fallbackY) {
        Vec3 pivot = operatorUnit.worldPivotPosition();
        Vec3 screen = VectorUtil.worldToScreen(pivot.add(aimDirection(operatorUnit).scale(256)));
        if (screen.z < 0) {
            return new double[]{fallbackX, fallbackY};
        }
        return new double[]{screen.x, screen.y};
    }
}
