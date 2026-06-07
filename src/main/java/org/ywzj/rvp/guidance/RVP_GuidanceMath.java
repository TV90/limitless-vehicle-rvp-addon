package org.ywzj.rvp.guidance;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileMotion;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSeekerData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Steering math adapted to RVP projectiles.
 */
public final class RVP_GuidanceMath {

    private RVP_GuidanceMath() {}

    public static boolean applyIntent(RVP_BaseBullet projectile, RVP_GuidanceIntent intent, RVP_GuidanceEffectiveConfig config) {
        if (!intent.success()) {
            return false;
        }
        if (intent.directMotion()) {
            if (intent.aimPoint() != null) {
                if (intent.sourceType() == RVP_EnumGuidanceType.MCLOS) {
                    Vec3 toTarget = intent.aimPoint().subtract(projectile.position());
                    RVP_WireGuidanceSteering.applyFromDirection(
                            projectile, toTarget, config.steering().getTurningFactor());
                    projectile.rememberGuidancePos(intent.aimPoint());
                    projectile.setTargetPos(intent.aimPoint());
                    return true;
                }
                if (intent.sourceType() == RVP_EnumGuidanceType.SACLOS) {
                    return guidanceToPos(projectile, intent.aimPoint(), config);
                }
                return directToPos(projectile, intent.aimPoint(), config);
            }
            if (intent.aimEntity() != null) {
                return directToEntity(projectile, intent.aimEntity(), config);
            }
            return false;
        }
        if (intent.aimEntity() != null) {
            return guidanceToTarget(projectile, intent.aimEntity(), config);
        }
        if (intent.aimPoint() != null) {
            return guidanceToPos(projectile, intent.aimPoint(), config);
        }
        return false;
    }

    public static boolean guidanceToPos(RVP_BaseBullet projectile, Vec3 target) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null) {
            return false;
        }
        RVP_GuidanceEffectiveConfig config = RVP_GuidanceConfigResolver.resolveEffective(
                data, projectile, RVP_EnumGuidanceType.NONE);
        return guidanceToPos(projectile, target, config);
    }

    public static boolean guidanceToPos(RVP_BaseBullet projectile, Vec3 target, RVP_GuidanceEffectiveConfig config) {
        if (target == null || config == null) {
            return false;
        }
        Vec3 toTarget = target.subtract(projectile.position());
        double distance = toTarget.length();
        if (distance < 1.0E-6) {
            return false;
        }

        Vec3 velocity = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getFlightSpeed(), velocity.length());
        Vec3 desired = toTarget.scale(speed / distance);

        Vector3f missileDir = new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z);
        Vector3f targetDir = new Vector3f((float) toTarget.x, (float) toTarget.y, (float) toTarget.z);
        if (missileDir.lengthSquared() < 1.0E-6f) {
            missileDir.set((float) desired.x, (float) desired.y, (float) desired.z);
        }
        double angle = Math.abs(missileDir.angle(targetDir));
        if (angle > Math.toRadians(config.steering().getMaxDegreeOfMissile())) {
            return false;
        }

        double turning = config.steering().getTurningFactor();
        if (config.steering().getTickEndHoming() > 0 && projectile.life <= config.steering().getTickEndHoming()) {
            turning = Math.min(1.0, turning * 1.5);
        }
        Vec3 next = new Vec3(
                velocity.x + (desired.x - velocity.x) * turning,
                velocity.y + (desired.y - velocity.y) * turning,
                velocity.z + (desired.z - velocity.z) * turning
        );
        applyVelocityAndRotation(projectile, next);
        return true;
    }

    public static boolean directToPos(RVP_BaseBullet projectile, Vec3 target) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null) {
            return false;
        }
        return directToPos(projectile, target, RVP_GuidanceConfigResolver.resolveEffective(
                data, projectile, RVP_EnumGuidanceType.NONE));
    }

    public static boolean directToPos(RVP_BaseBullet projectile, Vec3 target, RVP_GuidanceEffectiveConfig config) {
        if (target == null) {
            return false;
        }
        Vec3 toTarget = target.subtract(projectile.position());
        if (toTarget.lengthSqr() <= 1.0E-6) {
            return false;
        }
        double speed = Math.max(projectile.getFlightSpeed(), projectile.getDeltaMovement().length());
        applyVelocityAndRotation(projectile, toTarget.normalize().scale(Math.max(speed, 0.01)));
        projectile.rememberGuidancePos(target);
        return true;
    }

    private static boolean directToEntity(RVP_BaseBullet projectile, Entity target, RVP_GuidanceEffectiveConfig config) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        return directToPos(projectile, target.getBoundingBox().getCenter(), config);
    }

    public static boolean guidanceToTarget(RVP_BaseBullet projectile, Entity target) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null) {
            return false;
        }
        RVP_EnumGuidanceType type = data.getGuidanceData().getStages().stream()
                .flatMap(stage -> stage.getSources().stream())
                .map(RVP_GuidanceData.Source::getType)
                .findFirst()
                .orElse(RVP_EnumGuidanceType.NONE);
        return guidanceToTarget(projectile, target, RVP_GuidanceConfigResolver.resolveEffective(
                data, projectile, type));
    }

    public static boolean guidanceToTarget(RVP_BaseBullet projectile, Entity target, RVP_GuidanceEffectiveConfig config) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        Vec3 targetPos = target.position().add(0, target.getBbHeight() * 0.5, 0);
        if (config.steering().isPredictTargetPos()) {
            double speed = Math.max(projectile.getDeltaMovement().length(), projectile.getFlightSpeed());
            double time = speed <= 1.0E-4 ? 0 : projectile.position().distanceTo(targetPos) / speed;
            targetPos = targetPos.add(target.getDeltaMovement().scale(time));
        }

        if (config.activeSourceType() == RVP_EnumGuidanceType.SARH) {
            Entity viewer = projectile.getShooterVehicle() != null ? projectile.getShooterVehicle() : projectile.getOwner();
            if (viewer != null) {
                double semiAngle = angleFromViewer(viewer, targetPos);
                RVP_GuidanceSeekerData seeker = config.seeker();
                if (semiAngle > seeker.resolvedFov() || projectile.position().distanceTo(targetPos) > seeker.resolvedRange()) {
                    projectile.clearTarget();
                    return false;
                }
            }
        }

        projectile.rememberGuidancePos(targetPos);
        return guidanceToPos(projectile, targetPos, config);
    }

    public static boolean isWithinSeekerCone(RVP_BaseBullet projectile, Entity target, RVP_WeaponData data) {
        if (data == null) {
            return false;
        }
        return isWithinSeekerCone(projectile, target, RVP_GuidanceConfigResolver.resolveEffective(
                data, projectile, RVP_EnumGuidanceType.NONE));
    }

    public static boolean isWithinSeekerCone(RVP_BaseBullet projectile, Entity target, RVP_GuidanceEffectiveConfig config) {
        if (target == null || config == null) {
            return false;
        }
        RVP_GuidanceSeekerData seeker = config.seeker();
        if (!seeker.hasSeekerGeometry()) {
            return true;
        }
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        float range = seeker.resolvedRange();
        float fov = seeker.resolvedFov();
        if (projectile.position().distanceToSqr(targetCenter) > range * range) {
            return false;
        }
        Vec3 toTarget = targetCenter.subtract(projectile.position());
        Vec3 look = projectile.getLookAngle().normalize();
        if (look.lengthSqr() <= 1.0E-6 || toTarget.lengthSqr() <= 1.0E-6) {
            return true;
        }
        double dot = Mth.clamp(look.dot(toTarget.normalize()), -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= fov;
    }

    public static boolean isOnGround(Entity entity, float minHeight) {
        if (entity == null) {
            return true;
        }
        int groundY = entity.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                entity.getBlockX(), entity.getBlockZ());
        return entity.getY() - groundY < minHeight;
    }

    public static boolean isEntityNearGroundBlocks(Entity entity, int blocksBelow) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity.onGround()) {
            return true;
        }
        if (blocksBelow <= 0) {
            return false;
        }
        Level level = entity.level();
        int x = Mth.floor(entity.getX() + 0.5);
        int y = Mth.floor(entity.getY() + 0.5);
        int z = Mth.floor(entity.getZ() + 0.5);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < blocksBelow; i++) {
            pos.set(x, y - i, z);
            if (!level.getBlockState(pos).isAir()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isVehicleTarget(Entity entity) {
        return entity instanceof AbstractVehicle && entity.isAlive();
    }

    private static void applyVelocityAndRotation(RVP_BaseBullet projectile, Vec3 velocity) {
        projectile.setDeltaMovement(velocity);
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, velocity);
    }

    private static double angleFromViewer(Entity viewer, Vec3 pos) {
        Vec3 look = viewer.getLookAngle().normalize();
        Vec3 toTarget = pos.subtract(viewer.position()).normalize();
        double dot = Mth.clamp(look.dot(toTarget), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }
}
