package org.ywzj.rvp.weapon.laser;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Clipped laser segment used by server damage and client rendering.
 */
public record RVP_LaserBeam(
        Vec3 muzzle,
        Vec3 renderStart,
        Vec3 renderEnd,
        /** World hit position before surface inset; use for impact FX. */
        Vec3 impactPoint,
        boolean hitSomething,
        @Nullable Entity hitEntity,
        @Nullable BlockHitResult blockHit,
        /** 射线被烟雾弹（{@link RVP_SmokeEntity}）AABB 截断：光束止于云团表面，hitEntity 为 null，不结算任何伤害。 */
        boolean smokeBlocked) {

    private static final double MIN_DRAW_LENGTH = 0.05;

    public double beamLength() {
        return renderStart.distanceTo(renderEnd);
    }

    public boolean isDrawable() {
        return beamLength() >= MIN_DRAW_LENGTH;
    }

    public Vec3 beamDirection() {
        Vec3 delta = renderEnd.subtract(renderStart);
        double len = delta.length();
        if (len < 1.0E-8) {
            return Vec3.ZERO;
        }
        return delta.scale(1.0 / len);
    }
}
