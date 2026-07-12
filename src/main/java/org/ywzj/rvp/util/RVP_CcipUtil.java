package org.ywzj.rvp.util;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

public final class RVP_CcipUtil {

    private static final int MAX_TICKS = 1200;

    private RVP_CcipUtil() {}

    @Nullable
    public static Vec3 computeBombImpact(Level level, Vec3 startPos, Vec3 startVelocity, RVP_WeaponData data) {
        if (level == null || startPos == null || startVelocity == null || data == null) {
            return null;
        }
        double x = startPos.x;
        double y = startPos.y;
        double z = startPos.z;
        Vec3 velocity = startVelocity;
        double flightSpeed = Math.max(startVelocity.length(), 0.01D);
        boolean usesPropulsion = data.usesPropulsion();
        boolean bombDefaultGravity = data.getWeaponKind() == RVP_EnumWeaponKind.BOMB
                && !usesPropulsion
                && data.getGravity() == 0f;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (usesPropulsion) {
                velocity = stepPropulsionVelocity(velocity, data);
            } else {
                velocity = stepBallisticVelocity(velocity, data, bombDefaultGravity, flightSpeed);
            }
            flightSpeed = Math.max(velocity.length(), 0.01D);

            x += velocity.x;
            y += velocity.y;
            z += velocity.z;

            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            if (y <= groundY) {
                return new Vec3(x, groundY, z);
            }
        }
        return null;
    }

    private static Vec3 stepBallisticVelocity(Vec3 velocity, RVP_WeaponData data, boolean bombDefaultGravity, double flightSpeed) {
        double gravity = data.getGravity();
        if (bombDefaultGravity) {
            gravity = -PhysicsEngine.G;
        }
        Vec3 next = velocity.add(0.0D, gravity, 0.0D);
        next = applyMchHorizontalDrag(next, data.getDragInAir());
        if (data.getProjectileData().isConstantSpeed() && next.lengthSqr() > 1.0E-6) {
            next = next.normalize().scale(Math.max(flightSpeed, 0.01D));
        }
        return clampSpeed(next, data.getProjectileData().getMinSpeed(), data.getProjectileData().getMaxSpeed());
    }

    private static Vec3 stepPropulsionVelocity(Vec3 velocity, RVP_WeaponData data) {
        Vec3 next = velocity;
        double speedSqr = next.lengthSqr();
        if (speedSqr > 1.0E-12 && data.getResolvedDragCoefficient() > 0f) {
            next = next.add(next.normalize().scale(-data.getResolvedDragCoefficient() * speedSqr));
        }
        float gravity = data.getGravity();
        if (gravity != 0f) {
            next = next.add(0.0D, gravity, 0.0D);
        } else {
            next = next.subtract(0.0D, PhysicsEngine.G, 0.0D);
        }
        return clampSpeed(next, data.getProjectileData().getMinSpeed(), data.getProjectileData().getMaxSpeed());
    }

    private static Vec3 applyMchHorizontalDrag(Vec3 velocity, float drag) {
        if (drag <= 0f) {
            return velocity;
        }
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        double dirX = velocity.x / speed;
        double dirZ = velocity.z / speed;
        return new Vec3(
                velocity.x - dirX * drag,
                velocity.y,
                velocity.z - dirZ * drag
        );
    }

    private static Vec3 clampSpeed(Vec3 velocity, float minSpeed, float maxSpeed) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        float clampedMin = Math.max(minSpeed, 0f);
        float clampedMax = Math.max(maxSpeed, 0f);
        if (clampedMax > 0f && clampedMin > 0f && clampedMin > clampedMax) {
            clampedMin = 0f;
        }
        if (clampedMax > 0f && speed > clampedMax) {
            return velocity.normalize().scale(clampedMax);
        }
        if (clampedMin > 0f && speed < clampedMin) {
            return velocity.normalize().scale(clampedMin);
        }
        return velocity;
    }
}
