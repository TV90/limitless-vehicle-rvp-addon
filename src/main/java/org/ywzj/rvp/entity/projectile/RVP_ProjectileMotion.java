package org.ywzj.rvp.entity.projectile;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 弹道与朝向：直接对齐 {@link org.ywzj.vehicle.entity.weapon.BulletEntity} 与
 * {@link org.ywzj.vehicle.entity.weapon.MissileEntity#tickMove()}，供 RVP 全弹种复用。
 */
public final class RVP_ProjectileMotion {

    private static final double MISSILE_COAST_LERP = 0.2D;

    private RVP_ProjectileMotion() {}

    /**
     * {@link org.ywzj.vehicle.entity.weapon.BulletEntity#tick()} 运动与朝向（含 lerp）。
     */
    public static void tickCannonBullet(RVP_BulletEntity bullet) {
        Vec3 movement = bullet.getDeltaMovement();
        bullet.applyCannonFacingFromVelocity(movement, true);

        double mx = movement.x;
        double my = movement.y;
        double mz = movement.z;
        double nextPosX = bullet.getX() + mx;
        double nextPosY = bullet.getY() + my;
        double nextPosZ = bullet.getZ() + mz;
        bullet.setPos(nextPosX, nextPosY, nextPosZ);
        bullet.flightDistance += movement.length();

        float friction = bullet.cannonFriction;
        float gravity = bullet.cannonGravity;
        if (bullet.isInWater()) {
            friction = 0.4F;
            gravity *= 0.6F;
        }
        bullet.setDeltaMovement(bullet.getDeltaMovement().scale(1f - friction));
        bullet.setDeltaMovement(bullet.getDeltaMovement().add(0, -gravity, 0));
    }

    /**
     * {@link org.ywzj.vehicle.entity.weapon.MissileEntity#tickMove()} — 推力沿 {@code getLookAngle()}，
     * 点火后无目标时按速度归正朝向；RVP 在 {@code rotate_to_motion} 时每 tick 将朝向对齐速度后再推力。
     */
    public static void tickMissileMove(RVP_BaseBullet projectile) {
        RVP_WeaponData data = projectile.rvpData;
        if (data == null) {
            return;
        }

        Vec3 velocity = projectile.getDeltaMovement();
        int ignition = data.getResolvedIgnitionDelayTick();

        if (projectile.tickCount >= ignition) {
            if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
                applyMissileCoastFacing(projectile, velocity, 1.0F);
            }
            Vec3 lookDir = projectile.getLookAngle();
            int motorTick = projectile.tickCount - ignition;
            if (motorTick <= data.getResolvedMotorBurnTime()) {
                float mass = Math.max(data.getResolvedMass(), 1.0E-6f);
                double acceleration = data.getResolvedThrust() / mass;
                velocity = velocity.add(lookDir.scale(acceleration));
            }
            double speedSqr = velocity.lengthSqr();
            float dragCoeff = data.getResolvedDragCoefficient();
            if (speedSqr > 1.0E-12 && dragCoeff > 0) {
                velocity = velocity.add(velocity.normalize().scale(-dragCoeff * speedSqr));
            }
        }

        if (projectile.tickCount < ignition) {
            velocity = applyPreIgnitionVelocity(projectile, velocity, ignition);
        } else {
            velocity = applyPropulsionGravity(projectile, velocity, data);
        }

        velocity = clampSpeed(projectile, velocity, data);
        projectile.setDeltaMovement(velocity);
        projectile.setPos(projectile.position().add(velocity));
        projectile.flightDistance += velocity.length();
        projectile.flightSpeed = (float) Math.max(projectile.flightSpeed, velocity.length());

        if (projectile.tickCount >= ignition) {
            int motorTick = projectile.tickCount - ignition;
            boolean coasting = motorTick > data.getResolvedMotorBurnTime()
                    && projectile.getTargetEntity() == null
                    && projectile.getTargetPos() == null;
            if (data.getProjectileData().isRotateToMotion()) {
                if (coasting && velocity.lengthSqr() > 0.01) {
                    applyMissileCoastFacing(projectile, velocity, (float) MISSILE_COAST_LERP);
                } else if (velocity.lengthSqr() > 1.0E-6) {
                    applyMissileCoastFacing(projectile, velocity, 1.0F);
                }
            } else if (coasting && velocity.lengthSqr() > 0.01) {
                applyMissileCoastFacing(projectile, velocity, (float) MISSILE_COAST_LERP);
            }
        }
    }

    /** 发射完成：初速 + 可选载机速度已写入 {@code deltaMovement}。 */
    public static void finalizeSpawnOrientation(RVP_BaseBullet projectile, RVP_BaseBullet.AimRot aim) {
        Vec3 vel = projectile.getDeltaMovement();
        if (projectile.usesCannonBallistics()) {
            if (vel.lengthSqr() > 1.0E-8) {
                projectile.applyCannonFacingFromVelocity(vel, false);
            } else {
                projectile.applySpawnAimRot(aim);
            }
        } else {
            projectile.applySpawnAimRot(aim);
            if (projectile.rvpData != null
                    && projectile.rvpData.getProjectileData().isRotateToMotion()
                    && vel.lengthSqr() > 1.0E-6) {
                applyMissileCoastFacing(projectile, vel, 1.0F);
            }
        }
        projectile.yRotO = projectile.getYRot();
        projectile.xRotO = projectile.getXRot();
    }

    /**
     * 导弹无目标惯性段：{@link org.ywzj.vehicle.entity.weapon.MissileEntity#tickMove()} 归正公式。
     */
    public static void applyMissileCoastFacing(Entity entity, Vec3 velocity, float lerpFactor) {
        if (velocity.lengthSqr() <= 1.0E-6) {
            return;
        }
        Vec3 norm = velocity.normalize();
        double pitch = Math.toDegrees(-Math.asin(Mth.clamp(norm.y, -1.0, 1.0)));
        double yaw = Math.toDegrees(Math.atan2(norm.z, norm.x)) - 90.0;
        entity.setXRot((float) Mth.lerp(lerpFactor, entity.getXRot(), pitch));
        entity.setYRot((float) Mth.lerp(lerpFactor, entity.getYRot(), yaw));
    }

    /** 制导改速后朝向：与 {@link org.ywzj.vehicle.entity.weapon.MissileEntity} 转向一致，用 {@link VectorUtil#vecToRot}。 */
    public static void applyGuidanceFacing(Entity entity, Vec3 direction) {
        if (direction.lengthSqr() <= 1.0E-8) {
            return;
        }
        Vec2 rot = VectorUtil.vecToRot(direction);
        entity.setXRot(rot.x);
        entity.setYRot(rot.y);
    }

    public static void applyRotationFromVelocity(RVP_BaseBullet projectile, Vec3 velocity) {
        if (velocity.lengthSqr() <= 1.0E-6) {
            return;
        }
        RVP_WeaponData data = projectile.rvpData;
        if (data == null || !data.getProjectileData().isRotateToMotion()) {
            return;
        }
        if (projectile.usesCannonBallistics()) {
            projectile.applyCannonFacingFromVelocity(velocity, false);
        } else {
            applyMissileCoastFacing(projectile, velocity, 1.0F);
        }
    }

    private static Vec3 applyPreIgnitionVelocity(RVP_BaseBullet projectile, Vec3 velocity, int ignition) {
        AbstractVehicle carrier = projectile.shooterVehicle;
        if (carrier == null) {
            return velocity;
        }
        Vector3f[] axes = carrier.getMainCubeOBB().obb().getAxes();
        Vec3 eject = new Vec3(axes[1].negate());
        float muzzle = projectile.rvpData.getProjectileVelocity();
        if (muzzle > 1.0E-4f) {
            if (projectile.tickCount == 0) {
                return velocity.add(eject);
            }
            return velocity;
        }
        return carrier.getDeltaMovement().add(eject);
    }

    private static Vec3 applyPropulsionGravity(RVP_BaseBullet projectile, Vec3 velocity, RVP_WeaponData data) {
        if (projectile.isInWater()) {
            float waterGravity = data.getGravityInWater();
            if (waterGravity != 0f) {
                return velocity.add(0, waterGravity, 0);
            }
            return velocity.subtract(0, PhysicsEngine.G * 0.6f, 0);
        }
        float gravity = data.getGravity();
        if (gravity != 0f) {
            return velocity.add(0, gravity, 0);
        }
        return velocity.subtract(0, PhysicsEngine.G, 0);
    }

    public static Vec3 clampSpeed(RVP_BaseBullet projectile, Vec3 velocity, RVP_WeaponData data) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        float min = data.getProjectileData().getMinSpeed();
        float max = data.getProjectileData().getMaxSpeed();
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
