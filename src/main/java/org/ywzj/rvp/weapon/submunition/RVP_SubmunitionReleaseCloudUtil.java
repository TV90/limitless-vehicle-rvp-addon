package org.ywzj.rvp.weapon.submunition;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;

/**
 * 子弹药释放云位置采样工具；仅计算服务端权威初始位置，不参与速度合成。
 */
public final class RVP_SubmunitionReleaseCloudUtil {

    private RVP_SubmunitionReleaseCloudUtil() {}

    /**
     * 在以 {@code center} 为中心、X/Z 共用水平半径且 Y 使用竖直半径的均匀椭球体积内采样生成位置。
     * 关闭释放云或两个轴半径都为 0 时原样返回中心，并且不消耗随机数。
     */
    public static Vec3 samplePosition(Vec3 center, RVP_SubmunitionReleaseData release, RandomSource random) {
        // 调用本项目 release 数据访问器：未启用时保持既有同点生成行为并保留随机数序列。
        if (release == null || !release.isReleaseCloudEnabled()) {
            return center;
        }
        // 调用本项目 release 数据访问器：分别取得已经完成非有限值与负值处理的权威轴半径。
        double horizontalRadius = release.getReleaseCloudHorizontalRadius();
        double verticalRadius = release.getReleaseCloudVerticalRadius();
        if (horizontalRadius <= 0.0D && verticalRadius <= 0.0D) {
            return center;
        }

        double cosTheta = random.nextDouble() * 2.0D - 1.0D;
        double sinTheta = Math.sqrt(Math.max(0.0D, 1.0D - cosTheta * cosTheta));
        double azimuth = random.nextDouble() * Math.PI * 2.0D;
        double normalizedRadius = Math.cbrt(random.nextDouble());
        Vec3 offset = new Vec3(
                horizontalRadius * normalizedRadius * sinTheta * Math.cos(azimuth),
                verticalRadius * normalizedRadius * cosTheta,
                horizontalRadius * normalizedRadius * sinTheta * Math.sin(azimuth));
        return center.add(offset);
    }
}
