package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_Range;

public final class RVP_GuidanceRuntimeGeometry {

    private static final double DEFAULT_SCAN_RADIUS = 512.0;

    private RVP_GuidanceRuntimeGeometry() {}

    public static boolean passesTrackLimits(
            RVP_BaseBullet projectile,
            Vec3 target,
            RVP_GuidanceActiveConfig config
    ) {
        if (projectile == null || target == null || config == null) {
            return false;
        }
        if (!passesTrackEnvelope(projectile, target, config)) {
            return false;
        }
        return withinAngle(
                resolveTrackAxis(projectile),
                target.subtract(projectile.position()),
                config.maxGuidanceAngle());
    }

    public static boolean passesTrackEnvelope(
            RVP_BaseBullet projectile,
            Vec3 target,
            RVP_GuidanceActiveConfig config
    ) {
        if (projectile == null || target == null || config == null) {
            return false;
        }
        double distance = projectile.position().distanceTo(target);
        if (!contains(config.targetDistanceRange(), distance)) {
            return false;
        }
        if (!contains(config.altitudeRange(), altitudeAgl(projectile, target))) {
            return false;
        }
        return true;
    }

    public static boolean passesTrackLimits(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_GuidanceActiveConfig config
    ) {
        return target != null && target.isAlive()
                && passesTrackLimits(projectile, target.getBoundingBox().getCenter(), config);
    }

    public static boolean passesTrackEnvelope(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_GuidanceActiveConfig config
    ) {
        return target != null && target.isAlive()
                && passesTrackEnvelope(projectile, target.getBoundingBox().getCenter(), config);
    }

    public static boolean passesAcquireLimits(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_GuidanceActiveConfig config
    ) {
        if (projectile == null || target == null || !target.isAlive() || config == null) {
            return false;
        }
        Vec3 center = target.getBoundingBox().getCenter();
        double distance = projectile.position().distanceTo(center);
        if (!contains(config.targetDistanceRange(), distance)) {
            return false;
        }
        if (!contains(config.altitudeRange(), altitudeAgl(projectile, center))) {
            return false;
        }
        return withinAngle(projectile.getLookAngle(), center.subtract(projectile.position()),
                config.maxLockHalfAngle());
    }

    public static double resolveScanRadius(RVP_Range<Float> range) {
        if (range == null) {
            return DEFAULT_SCAN_RADIUS;
        }
        double maximum = 0.0;
        for (RVP_Range.Interval<Float> interval : range.intervals()) {
            if (interval.upper() == null) {
                return Math.max(DEFAULT_SCAN_RADIUS, maximum);
            }
            maximum = Math.max(maximum, interval.upper());
        }
        return Math.max(maximum, 1.0);
    }

    public static boolean withinAngle(Vec3 axis, Vec3 toTarget, double maxAngle) {
        if (axis == null || toTarget == null || axis.lengthSqr() <= 1.0E-8 || toTarget.lengthSqr() <= 1.0E-8) {
            return true;
        }
        double dot = Mth.clamp(axis.normalize().dot(toTarget.normalize()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot)) <= Math.max(maxAngle, 0.0) + 1.0E-6;
    }

    /**
     * 把期望方向钳制到与轴向量夹角不超过 {@code maxAngle} 的锥面边缘。
     *
     * <p>未超角时原样返回归一化方向；超出时返回"贴边"方向——在轴与期望方向所成平面内、
     * 与轴恰好成 {@code maxAngle}。供 HITL 人手直控转向使用：指令超角不拒绝、贴边尽量转，
     * 避免绕大圈攻击时因夹角恒超限导致导弹整 tick 拒转直飞（2026-09-10）。</p>
     */
    public static Vec3 clampToAngle(Vec3 axis, Vec3 toTarget, double maxAngle) {
        if (axis == null || toTarget == null || axis.lengthSqr() <= 1.0E-8 || toTarget.lengthSqr() <= 1.0E-8) {
            return toTarget == null ? Vec3.ZERO : toTarget;
        }
        Vec3 axisNorm = axis.normalize();
        Vec3 dirNorm = toTarget.normalize();
        if (withinAngle(axisNorm, dirNorm, maxAngle)) {
            return dirNorm;
        }
        double limitRad = Math.toRadians(Math.max(maxAngle, 0.0));
        // 正交分解：期望方向 = 沿轴分量 + 垂直分量；超角时把沿轴分量替换为 cos(limit)、垂直分量归一并缩放为 sin(limit)
        double along = dirNorm.dot(axisNorm);
        Vec3 perp = dirNorm.subtract(axisNorm.scale(along));
        if (perp.lengthSqr() <= 1.0E-8) {
            // 期望方向与轴共线且超角（≈反向 180°）：任取与轴垂直的方向作为贴边平面
            perp = Math.abs(axisNorm.x) <= 0.9D
                    ? new Vec3(0.0D, -axisNorm.z, axisNorm.y)
                    : new Vec3(-axisNorm.y, axisNorm.x, 0.0D);
        }
        perp = perp.normalize();
        return axisNorm.scale(Math.cos(limitRad)).add(perp.scale(Math.sin(limitRad)));
    }

    private static Vec3 resolveTrackAxis(RVP_BaseBullet projectile) {
        if (projectile == null) {
            return Vec3.ZERO;
        }
        Vec3 motion = projectile.getDeltaMovement();
        if (motion != null && motion.lengthSqr() > 1.0E-8) {
            return motion;
        }
        return projectile.getLookAngle();
    }

    private static boolean contains(RVP_Range<Float> range, double value) {
        return range == null || range.contains((float) value);
    }

    private static double altitudeAgl(RVP_BaseBullet projectile, Vec3 target) {
        int x = Mth.floor(target.x);
        int z = Mth.floor(target.z);
        int groundY = projectile.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return target.y - groundY;
    }
}
