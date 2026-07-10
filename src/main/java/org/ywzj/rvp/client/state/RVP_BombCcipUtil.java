package org.ywzj.rvp.client.state;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.PhysicsEngine;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Client-side CCIP prediction for {@code rvp:bomb}, mirroring RVP bomb ballistics
 * instead of the base-mod {@code VehicleAerialBomb} motion model.
 */
public final class RVP_BombCcipUtil {

    private static final int MAX_TICKS = 1200;

    private RVP_BombCcipUtil() {}

    @Nullable
    public static Vec3 computeImpact(AbstractVehicle vehicle, WeaponUnit weaponUnit, RVP_WeaponData data) {
        if (vehicle == null || weaponUnit == null || data == null) {
            return null;
        }
        WeaponUnit aimUnit = weaponUnit;
        if (aimUnit.isParentWeaponUnitAim()) {
            aimUnit = aimUnit.getRootParentWeaponUnit();
        }
        Vec3 startPos = aimUnit.worldPivotPosition();
        Vec2 rot = aimUnit.worldRot();
        Vec3 startVelocity = resolveInitialVelocity(vehicle, rot, data);
        return computeImpact(vehicle.level(), startPos, startVelocity, rot, data);
    }

    @Nullable
    public static Vec3 computeImpact(Level level, Vec3 startPos, Vec3 startVelocity, Vec2 launchRot, RVP_WeaponData data) {
        if (level == null || startPos == null || startVelocity == null || launchRot == null || data == null) {
            return null;
        }
        double x = startPos.x;
        double y = startPos.y;
        double z = startPos.z;
        Vec3 velocity = startVelocity;
        double flightSpeed = Math.max(startVelocity.length(), 0.01D);

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            velocity = data.usesPropulsion()
                    ? stepPropulsionBomb(velocity, launchRot, tick, data)
                    : stepBallisticBomb(velocity, data);
            if (data.getProjectileData().isConstantSpeed() && velocity.lengthSqr() > 1.0E-6) {
                velocity = velocity.normalize().scale(Math.max(flightSpeed, 0.01D));
            }
            velocity = clampSpeed(velocity, data.getProjectileData());
            x += velocity.x;
            y += velocity.y;
            z += velocity.z;
            flightSpeed = Math.max(velocity.length(), 0.01D);

            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            if (y <= groundY) {
                return new Vec3(x, groundY, z);
            }
        }
        return null;
    }

    private static Vec3 resolveInitialVelocity(AbstractVehicle vehicle, Vec2 rot, RVP_WeaponData data) {
        Vec3 aimDir = VectorUtil.rotToVec(rot.x, rot.y).normalize();
        double muzzleSpeed = Math.max(data.resolveMuzzleSpeed(data.getWeaponKind()), 0.01f);
        Vec3 velocity = aimDir.scale(muzzleSpeed);
        if (data.isInheritVehicleVelocity()) {
            velocity = velocity.add(vehicle.getDeltaMovement());
        }
        return velocity;
    }

    private static Vec3 stepBallisticBomb(Vec3 velocity, RVP_WeaponData data) {
        float gravity = data.getGravity();
        if (gravity != 0f) {
            velocity = velocity.add(0, gravity, 0);
        } else {
            velocity = velocity.add(0, -PhysicsEngine.G, 0);
        }
        return applyMchHorizontalDrag(velocity, data.getDragInAir());
    }

    private static Vec3 stepPropulsionBomb(Vec3 velocity, Vec2 launchRot, int tick, RVP_WeaponData data) {
        Vec3 thrustDir = velocity.lengthSqr() > 1.0E-6
                ? velocity.normalize()
                : VectorUtil.rotToVec(launchRot.x, launchRot.y).normalize();
        int ignition = data.getResolvedIgnitionDelayTick();
        if (tick >= ignition) {
            int motorTick = tick - ignition;
            if (motorTick <= data.getResolvedMotorBurnTime()) {
                float mass = Math.max(data.getResolvedMass(), 1.0E-6f);
                double acceleration = data.getResolvedThrust() / mass;
                velocity = velocity.add(thrustDir.scale(acceleration));
            }
            double speedSqr = velocity.lengthSqr();
            float dragCoeff = data.getResolvedDragCoefficient();
            if (speedSqr > 1.0E-12 && dragCoeff > 0f) {
                velocity = velocity.add(velocity.normalize().scale(-dragCoeff * speedSqr));
            }
        }

        float gravity = data.getGravity();
        if (gravity != 0f) {
            return velocity.add(0, gravity, 0);
        }
        return velocity.add(0, -PhysicsEngine.G, 0);
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

    private static Vec3 clampSpeed(Vec3 velocity, RVP_ProjectileData projectileData) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        float min = projectileData.getMinSpeed();
        float max = projectileData.getMaxSpeed();
        if (max > 0f && min > 0f && min > max) {
            min = 0f;
        }
        if (max > 0f && speed > max) {
            return velocity.normalize().scale(max);
        }
        if (min > 0f && speed < min) {
            return velocity.normalize().scale(min);
        }
        return velocity;
    }
}
