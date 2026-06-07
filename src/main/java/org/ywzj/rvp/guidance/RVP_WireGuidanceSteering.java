package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.source.RVP_MclosGuidanceSource;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Optional;

/**
 * MCH 拖线指令解析与瞬时运动对齐，供 {@link org.ywzj.rvp.guidance.source.RVP_MclosGuidanceSource}
 * 与 {@link RVP_GuidanceMath}（{@code take_over_motion}）在分段制导架构内调用。
 */
public final class RVP_WireGuidanceSteering {

    private RVP_WireGuidanceSteering() {}

    /**
     * @return Vec2(pitch, yaw) in vehicle/Minecraft convention.
     */
    public static Optional<Vec2> resolveCommand(RVP_BaseBullet projectile) {
        if (projectile instanceof RVP_MissileEntity missile && missile.rvp$isHitlMouseSteering()) {
            return Optional.of(new Vec2(missile.rvp$getHitlSteeringPitch(), missile.rvp$getHitlSteeringYaw()));
        }
        return resolveCockpitCommand(projectile);
    }

    private static Optional<Vec2> resolveCockpitCommand(RVP_BaseBullet projectile) {
        Vec3 dir = RVP_MclosGuidanceSource.operatorAimDirection(projectile.getShooterWeaponUnit());
        AbstractVehicle vehicle = projectile.getShooterVehicle();
        if (dir == null && vehicle != null && projectile.getOwner() instanceof LivingEntity operator
                && vehicle.getOwnOperatorUnit(operator) instanceof WeaponUnit operatorUnit) {
            dir = RVP_MclosGuidanceSource.operatorAimDirection(operatorUnit);
        }
        if (dir == null) {
            return Optional.empty();
        }
        return Optional.of(VectorUtil.vecToRot(dir));
    }

    /**
     * MCH {@code setMotion + setRotation} — invoked from {@link RVP_GuidanceMath#directToPos}
     * when MCLOS {@code take_over_motion} is active.
     */
    public static void applyFromDirection(RVP_BaseBullet projectile, Vec3 direction) {
        applyFromDirection(projectile, direction, 1.0);
    }

    /** @param turningFactor 0–1 per-tick blend toward command heading; 1 = instant wire snap. */
    public static void applyFromDirection(RVP_BaseBullet projectile, Vec3 direction, double turningFactor) {
        if (direction.lengthSqr() <= 1.0E-6) {
            return;
        }
        Vec3 dir = direction.normalize();

        double speed = projectile.getDeltaMovement().length();
        if (projectile.getRvpData() != null) {
            speed = Math.max(speed, projectile.getFlightSpeed());
            if (projectile.getRvpData().getProjectileData().isConstantSpeed()) {
                speed = Math.max(projectile.getFlightSpeed(), projectile.getRvpData().getProjectileVelocity());
            }
        }
        speed = Math.max(speed, 0.01);

        Vec3 desired = dir.scale(speed);
        Vec3 velocity = desired;
        double blend = Mth.clamp(turningFactor, 0.0, 1.0);
        Vec3 current = projectile.getDeltaMovement();
        if (blend < 1.0 && current.lengthSqr() > 1.0E-6) {
            velocity = new Vec3(
                    current.x + (desired.x - current.x) * blend,
                    current.y + (desired.y - current.y) * blend,
                    current.z + (desired.z - current.z) * blend);
            double blendedSpeed = velocity.length();
            if (blendedSpeed > 1.0E-6) {
                velocity = velocity.scale(speed / blendedSpeed);
            }
        }

        Vec2 rot = VectorUtil.vecToRot(velocity.normalize());
        float pitch = Mth.clamp(rot.x, -89.9f, 89.9f);
        float yaw = Mth.wrapDegrees(rot.y);

        projectile.setYRot(yaw);
        projectile.setXRot(pitch);
        projectile.yRotO = yaw;
        projectile.xRotO = pitch;
        projectile.setDeltaMovement(velocity);
        projectile.rvp$markGuidanceWireDirectApplied();
    }
}
