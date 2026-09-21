package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 雷达与红外头瞄共用的纯几何计算。
 *
 * <p>锁定判定不只比较准线与目标包围盒中心的夹角，而是把包围盒近似为外接球，
 * 用外接球在观察点形成的角半径表示目标可见轮廓。这样准线落在机翼、车体边缘等
 * 可见部分时仍能捕获目标，同时不会改变各传感器自身的距离、高度、视线或机械边界。</p>
 */
public final class RVP_HmdTargetingMath {

    private RVP_HmdTargetingMath() {}

    /**
     * 计算扫描方向到目标可见轮廓的最小角距离。
     *
     * @param origin 扫描起点
     * @param scanDirection 扫描方向，无需预先归一化
     * @param bounds 目标世界坐标包围盒
     * @return 角距离（度）；参数无效时返回正无穷
     */
    public static double effectiveAngularMissDeg(Vec3 origin, Vec3 scanDirection, AABB bounds) {
        if (origin == null || scanDirection == null || bounds == null
                || scanDirection.lengthSqr() <= 1.0E-8) {
            return Double.POSITIVE_INFINITY;
        }

        Vec3 centerOffset = bounds.getCenter().subtract(origin);
        double centerDistance = centerOffset.length();
        if (centerDistance <= 1.0E-6) {
            return 0.0;
        }

        double centerDot = Mth.clamp(
                scanDirection.normalize().dot(centerOffset.scale(1.0 / centerDistance)), -1.0, 1.0);
        double centerAngleDeg = Math.toDegrees(Math.acos(centerDot));

        double halfX = bounds.getXsize() * 0.5;
        double halfY = bounds.getYsize() * 0.5;
        double halfZ = bounds.getZsize() * 0.5;
        double boundingRadius = Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ);
        if (boundingRadius >= centerDistance) {
            return 0.0;
        }

        double angularRadiusDeg = Math.toDegrees(Math.asin(Mth.clamp(
                boundingRadius / centerDistance, 0.0, 1.0)));
        return Math.max(0.0, centerAngleDeg - angularRadiusDeg);
    }
}
