package org.ywzj.rvp.entity.projectile;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.rvp.debug.RVP_DualPulseDebug;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;
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
        int ignition = resolveMotorIgnitionTick(projectile, data);

        if (projectile.getFlightTickCount() >= ignition) {
            if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
                applyMissileCoastFacing(projectile, velocity, 1.0F);
            }
            Vec3 lookDir = projectile.getLookAngle();
            int motorTick = projectile.getFlightTickCount() - ignition;
            float burn1 = data.getResolvedMotorBurnTime();
            boolean burning1 = motorTick <= burn1;
            boolean burning2 = false;
            if (!burning1
                    && projectile.isMissile()
                    && data.getProjectileData().usesSecondPulse()
                    && isDualPulseSupportedMissile(data)) {
                if (projectile.secondPulseStartTick < 0 && shouldStartSecondPulse(projectile, data, velocity)) {
                    projectile.secondPulseStartTick = projectile.getFlightTickCount();
                    projectile.getEntityData().set(RVP_BaseBullet.DATA_SECOND_PULSE_START_TICK, projectile.secondPulseStartTick);
                    projectile.getEntityData().set(
                            RVP_BaseBullet.DATA_SECOND_PULSE_BURN_TIME_TICK,
                            Math.round(data.getProjectileData().getResolvedSecondPulseBurnTime())
                    );
                    RVP_DualPulseDebug.noteSecondPulseStarted(projectile, data);
                }
                if (projectile.secondPulseStartTick >= 0) {
                    int t2 = projectile.getFlightTickCount() - projectile.secondPulseStartTick;
                    burning2 = t2 >= 0 && t2 <= data.getProjectileData().getResolvedSecondPulseBurnTime();
                }
            }
            if (burning1 || burning2) {
                float mass;
                float thrust;
                if (burning1) {
                    // 主燃烧段：按点火后 Tick 解析推力曲线与变质量（A1 变质量 / A2 推力曲线）。
                    thrust = data.getProjectileData().resolveThrustAt(motorTick);
                    mass = data.getProjectileData().resolveMassAt(motorTick, burn1);
                } else {
                    // 第二脉冲：沿用标量推力，质量取主燃烧结束后的干质量。
                    thrust = data.getProjectileData().getResolvedSecondPulseThrust();
                    mass = data.getProjectileData().resolveMassAt((int) burn1, burn1);
                }
                // 调用本项目推力加速度换算，保证实体/虚拟链单位一致（A3）。
                double acceleration = RVP_BallisticTrajectoryMath.thrustAccelerationPerTick(thrust, mass);
                velocity = velocity.add(lookDir.scale(acceleration));
            }
            double speedSqr = velocity.lengthSqr();
            float dragCoeff = data.getResolvedDragCoefficient() * resolveMissileAltitudeDragFactor(projectile, data);
            if (speedSqr > 1.0E-12 && dragCoeff > 0) {
                velocity = velocity.add(velocity.normalize().scale(-dragCoeff * speedSqr));
            }
        }

        if (projectile.getFlightTickCount() < ignition) {
            velocity = applyPreIgnitionVelocity(projectile, velocity, ignition);
        } else {
            // 施加沿y轴向下的PhysicsEngine.G 武器配置里面gravity设置为0也不能避免
            velocity = applyPropulsionGravity(projectile, velocity, data);
        }

        velocity = clampSpeed(projectile, velocity, data);
        projectile.setDeltaMovement(velocity);
        projectile.setPos(projectile.position().add(velocity));
        projectile.flightDistance += velocity.length();
        projectile.flightSpeed = (float) Math.max(projectile.flightSpeed, velocity.length());

        if (projectile.getFlightTickCount() >= ignition) {
            int motorTick = projectile.getFlightTickCount() - ignition;
            boolean coasting = motorTick > data.getResolvedMotorBurnTime()
                    && (projectile.secondPulseStartTick < 0
                        || projectile.getFlightTickCount() - projectile.secondPulseStartTick > data.getProjectileData().getResolvedSecondPulseBurnTime())
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

    /**
     * 二级脉冲支持的制导类型白名单：IR/ARH/SARH/ARM（空空弹传统双脉冲）+ GPS
     * （弹道/准弹道导弹的两级推进，如 9M723 一级助推 + 二级脉冲接力；2026-09-15 应用户要求加入）。
     * 门控仅在 {@code second_pulse} 显式配置时生效——未配置该字段的 GPS 导弹行为不变。
     */
    private static boolean isDualPulseSupportedMissile(RVP_WeaponData data) {
        return data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARM)
                || data.usesGuidanceType(RVP_EnumGuidanceType.GPS);
    }

    private static boolean shouldStartSecondPulse(RVP_BaseBullet projectile, RVP_WeaponData data, Vec3 velocity) {
        int ignition = resolveMotorIgnitionTick(projectile, data);
        int motorTick = projectile.getFlightTickCount() - ignition;
        if (motorTick <= data.getResolvedMotorBurnTime()) {
            return false;
        }

        float speedThreshold = data.getProjectileData().getResolvedSecondPulseTriggerSpeed();
        float distThreshold = data.getProjectileData().getResolvedSecondPulseTriggerDistance();
        boolean speedEnabled = speedThreshold > 0f;
        boolean distEnabled = distThreshold > 0f;

        boolean speedOk = false;
        if (speedEnabled) {
            speedOk = velocity.length() <= speedThreshold;
        }

        boolean distOk = false;
        double resolvedDistance = -1D;
        String distanceSource = null;
        Entity target = null;
        Vec3 targetPos = null;
        Vec3 lastGuidancePos = projectile.getLastGuidancePos();
        if (distEnabled) {
            target = projectile.getTargetEntity();
            if (target != null && target.isAlive()) {
                targetPos = projectile.aimPoint(target);
                resolvedDistance = projectile.position().distanceTo(targetPos);
                distanceSource = "targetEntity";
                distOk = resolvedDistance <= distThreshold;
            } else {
                targetPos = projectile.getTargetPos();
                if (targetPos == null) {
                    targetPos = lastGuidancePos;
                    if (targetPos != null) {
                        distanceSource = "lastGuidancePos";
                    }
                } else {
                    distanceSource = "targetPos";
                }
                if (targetPos != null) {
                    resolvedDistance = projectile.position().distanceTo(targetPos);
                    distOk = resolvedDistance <= distThreshold;
                }
            }
        }

        RVP_DualPulseDebug.noteEvaluation(
                projectile,
                data,
                velocity,
                ignition,
                motorTick,
                data.getResolvedMotorBurnTime(),
                speedEnabled,
                speedThreshold,
                speedOk,
                distEnabled,
                distThreshold,
                distOk,
                target,
                targetPos,
                lastGuidancePos,
                resolvedDistance,
                distanceSource
        );

        return speedOk || distOk;
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

    /**
     * TV / HITL MOUSE flight: speed along {@link RVP_BaseBullet#getLookAngle()}, no {@code rotate_to_motion}.
     */
    public static void tickHitlTvMove(RVP_MissileEntity missile) {
        RVP_WeaponData data = missile.rvpData;
        if (data == null) {
            return;
        }
        Vec3 velocity = missile.getDeltaMovement();
        Vec3 lookDir = missile.getLookAngle();
        int ignition = resolveMotorIgnitionTick(missile, data);

        if (missile.getFlightTickCount() < ignition) {
            velocity = applyPreIgnitionVelocity(missile, velocity, ignition);
        } else {
            double speed = Math.max(velocity.length(), Math.max(missile.flightSpeed, data.getProjectileVelocity()));
            int motorTick = missile.getFlightTickCount() - ignition;
            if (motorTick <= data.getResolvedMotorBurnTime()) {
                // 主燃烧段：按点火后 Tick 解析推力曲线与变质量（A1 变质量 / A2 推力曲线）。
                float mass = data.getProjectileData().resolveMassAt(motorTick, data.getResolvedMotorBurnTime());
                float thrust = data.getProjectileData().resolveThrustAt(motorTick);
                // 调用本项目推力加速度换算，保证单位一致（A3）。
                speed += RVP_BallisticTrajectoryMath.thrustAccelerationPerTick(thrust, mass);
            }
            float dragCoeff = data.getResolvedDragCoefficient() * resolveMissileAltitudeDragFactor(missile, data);
            if (dragCoeff > 0 && speed > 0) {
                speed -= dragCoeff * speed * speed;
                speed = Math.max(speed, 0.01);
            }
            if (data.getProjectileData().isConstantSpeed()) {
                speed = Math.max(missile.flightSpeed, data.getProjectileVelocity());
            }
            velocity = lookDir.scale(speed);
            velocity = applyPropulsionGravity(missile, velocity, data);
            velocity = clampSpeed(missile, velocity, data);
        }

        missile.setDeltaMovement(velocity);
        missile.setPos(missile.position().add(velocity));
        missile.flightDistance += velocity.length();
        missile.flightSpeed = (float) Math.max(missile.flightSpeed, velocity.length());
    }

    private static int resolveMotorIgnitionTick(RVP_BaseBullet projectile, RVP_WeaponData data) {
        if (projectile == null || data == null) {
            return 0;
        }
        return Math.max(data.getResolvedIgnitionDelayTick(), projectile.getColdLaunchTimeTick());
    }

    private static Vec3 applyPreIgnitionVelocity(RVP_BaseBullet projectile, Vec3 velocity, int ignition) {
        AbstractVehicle carrier = projectile.shooterVehicle;
        if (carrier == null) {
            return velocity;
        }
        int coldLaunchTick = projectile.getColdLaunchTimeTick();
        Vector3f[] axes = carrier.getMainCubeOBB().obb().getAxes();
        if (coldLaunchTick > 0 && projectile.getFlightTickCount() < coldLaunchTick) {
            Vec3 configured = projectile.getColdLaunchVelocity();
            Vec3 launchVelocity = new Vec3(axes[0]).scale(configured.x)
                    .add(new Vec3(axes[1]).scale(configured.y))
                    .add(new Vec3(axes[2]).scale(configured.z));
            return carrier.getDeltaMovement().add(launchVelocity);
        }
        Vec3 eject = new Vec3(axes[1].negate());
        float muzzle = projectile.rvpData.getProjectileVelocity();
        if (muzzle > 1.0E-4f) {
            if (projectile.getFlightTickCount() == 0) {
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
//        if (gravity != 0f) {
        // 不再对rvp:missile导弹默认施加 - PhysicsEngine.G
        return velocity.add(0, gravity, 0);
//        }
//        return velocity.subtract(0, PhysicsEngine.G, 0);
    }

    static float resolveMissileAltitudeDragFactor(RVP_BaseBullet projectile, RVP_WeaponData data) {
        if (projectile == null || data == null || !projectile.isMissile()) {
            return 1.0f;
        }
        return data.getProjectileData().resolveAltitudeDragFactor(projectile.getY());
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
