package org.ywzj.rvp.weapon.impact;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 导弹空中目标命中视觉碎片的无状态几何与阻尼数学。 */
public final class RVP_MissileAirTargetImpactFragmentMath {
    /** 归一化向量的最小长度平方。 */
    private static final double MIN_DIRECTION_LENGTH_SQR = 1.0E-12D;

    private RVP_MissileAirTargetImpactFragmentMath() {
    }

    /**
     * 计算目标 AABB 沿行进方向的远侧外点。
     *
     * @param targetBox 目标当前世界 AABB
     * @param travelDirection 已归一化或可归一化的导弹行进方向
     * @param offset 目标外侧额外偏移，单位格
     * @return 位于目标远侧的生成点；无效方向时返回 AABB 中心
     */
    public static Vec3 resolveFarSide(AABB targetBox, Vec3 travelDirection, double offset) {
        if (targetBox == null || travelDirection == null || !isFinite(travelDirection)) {
            return targetBox == null ? Vec3.ZERO : targetBox.getCenter();
        }
        double lengthSqr = travelDirection.lengthSqr();
        if (lengthSqr <= MIN_DIRECTION_LENGTH_SQR) {
            return targetBox.getCenter();
        }
        Vec3 direction = travelDirection.scale(1.0D / Math.sqrt(lengthSqr));
        double halfX = targetBox.getXsize() * 0.5D;
        double halfY = targetBox.getYsize() * 0.5D;
        double halfZ = targetBox.getZsize() * 0.5D;
        double supportDistance = Math.abs(direction.x) * halfX
                + Math.abs(direction.y) * halfY
                + Math.abs(direction.z) * halfZ;
        return targetBox.getCenter().add(direction.scale(supportDistance + Math.max(offset, 0.0D)));
    }

    /** 返回与行进方向正交的稳定二维基底，用于碎片出生位置分散。 */
    public static OrthonormalBasis perpendicularBasis(Vec3 travelDirection) {
        Vec3 direction = normalizeOrZero(travelDirection);
        if (direction.lengthSqr() <= MIN_DIRECTION_LENGTH_SQR) {
            direction = new Vec3(0.0D, 0.0D, 1.0D);
        }
        Vec3 reference = Math.abs(direction.y) < 0.9D
                ? new Vec3(0.0D, 1.0D, 0.0D)
                : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 first = direction.cross(reference).normalize();
        Vec3 second = direction.cross(first).normalize();
        return new OrthonormalBasis(direction, first, second);
    }

    /** 对速度应用一 tick 阻尼。 */
    public static Vec3 applyDamping(Vec3 velocity, double damping) {
        if (velocity == null || !isFinite(velocity)) {
            return Vec3.ZERO;
        }
        double factor = Math.max(0.0D, Math.min(1.0D, damping));
        return velocity.scale(factor);
    }

    /** 判断速度是否已达到视觉上的归零阈值。 */
    public static boolean isStopped(Vec3 velocity, double stopSpeed) {
        if (velocity == null || !isFinite(velocity)) {
            return true;
        }
        double threshold = Math.max(stopSpeed, 0.0D);
        return velocity.lengthSqr() <= threshold * threshold;
    }

    /**
     * 按线段长度计算白烟轨迹采样点数量，保证相邻点不超过目标间距并限制单次补点上限。
     *
     * @param segmentLength 本次需要补烟的轨迹线段长度，单位格
     * @param pointSpacing 采样点最大间距，单位格
     * @param maxPoints 单次允许生成的最大采样点数
     * @return 至少一个、且不超过上限的采样点数量
     */
    public static int resolveSmokePointCount(double segmentLength, double pointSpacing, int maxPoints) {
        int safeMaxPoints = Math.max(maxPoints, 1);
        if (!Double.isFinite(segmentLength) || !Double.isFinite(pointSpacing)
                || segmentLength <= 0.0D || pointSpacing <= 0.0D) {
            return 1;
        }
        double requiredPoints = Math.ceil(segmentLength / pointSpacing);
        if (!Double.isFinite(requiredPoints)) {
            return safeMaxPoints;
        }
        return (int) Math.min(safeMaxPoints, Math.max(1.0D, requiredPoints));
    }

    /**
     * 在 1～指定上限的闭区间内随机决定本次视觉碎片数量。
     *
     * @param random 服务端随机源
     * @param maxCount 本次事件允许的碎片数量上限
     * @return 1～上限之间的随机数量
     */
    public static int randomFragmentCount(RandomSource random, int maxCount) {
        int safeMaxCount = Math.max(maxCount, 1);
        if (random == null) {
            return safeMaxCount;
        }
        return 1 + random.nextInt(safeMaxCount);
    }

    /**
     * 在给定轴线周围的圆锥体内均匀采样一个单位方向向量。
     *
     * @param axis 圆锥轴线，通常为导弹命中时速度方向
     * @param halfAngleRadians 圆锥相对轴线的最大偏转半角，单位弧度
     * @param random 客户端确定性随机源
     * @return 圆锥范围内的单位方向；轴线无效时返回零向量
     */
    public static Vec3 randomDirectionInCone(Vec3 axis, double halfAngleRadians, RandomSource random) {
        Vec3 direction = normalizeOrZero(axis);
        if (direction.lengthSqr() <= MIN_DIRECTION_LENGTH_SQR || random == null) {
            return direction;
        }
        double safeHalfAngle = Math.max(0.0D, Math.min(Math.PI, halfAngleRadians));
        if (safeHalfAngle <= MIN_DIRECTION_LENGTH_SQR) {
            return direction;
        }
        // cos(theta) 均匀采样，避免在圆锥轴线附近产生不自然的方向聚集。
        double minimumCosine = Math.cos(safeHalfAngle);
        double cosine = minimumCosine + random.nextDouble() * (1.0D - minimumCosine);
        double sine = Math.sqrt(Math.max(0.0D, 1.0D - cosine * cosine));
        double azimuth = random.nextDouble() * Math.PI * 2.0D;
        OrthonormalBasis basis = perpendicularBasis(direction);
        return direction.scale(cosine)
                .add(basis.first().scale(sine * Math.cos(azimuth)))
                .add(basis.second().scale(sine * Math.sin(azimuth)))
                .normalize();
    }

    /** 判断向量的三个分量是否均为有限数。 */
    public static boolean isFinite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static Vec3 normalizeOrZero(Vec3 vector) {
        if (!isFinite(vector) || vector.lengthSqr() <= MIN_DIRECTION_LENGTH_SQR) {
            return Vec3.ZERO;
        }
        return vector.normalize();
    }

    /** 行进方向及其横向两个正交基向量。 */
    public record OrthonormalBasis(
            /** 归一化的导弹行进方向。 */
            Vec3 direction,
            /** 第一个横向正交基向量。 */
            Vec3 first,
            /** 第二个横向正交基向量。 */
            Vec3 second) {
    }
}
