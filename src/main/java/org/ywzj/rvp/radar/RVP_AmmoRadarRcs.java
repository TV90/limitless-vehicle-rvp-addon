package org.ywzj.rvp.radar;

/**
 * 弹药分角度雷达信号因子（2026-09-17）：与战机 {@link RVP_AspectRcs} 同款 sin³ 三档插值，
 * 但有两点弹药特化——
 * <ul>
 *   <li>参照方向为**弹体飞行方向**：迎头（弹头指向观察者）信号最小、侧掠居中、飞离最大，
 *       物理上对应导弹迎头 RCS 极小；采用三维方向而非战机的水平面投影（俯冲/垂直目标物理正确）；</li>
 *   <li>无 ≤1 封顶：弹药可大于 1 增透（大直径弹比雷达标称更显眼）。</li>
 * </ul>
 * 纯 java.lang.Math 实现、不引用任何 Minecraft 类型，便于 JUnit 直测。
 */
public final class RVP_AmmoRadarRcs {

    /** sin³ 缓动（与战机 CURVE_POWER=1.5 → 指数 2×1.5=3 一致）。 */
    private static final double CURVE_EXPONENT = 3.0D;

    private RVP_AmmoRadarRcs() {
    }

    /**
     * 按弹体飞行方向与"弹体→观察者"方向的夹角插值雷达信号因子。
     *
     * @param dirX/dirY/dirZ     弹体飞行方向（无需归一化；零向量回退侧向值）
     * @param posX/Y/Z           弹体当前位置
     * @param obsX/obsY/obsZ     观察者（雷达/导引头）位置
     * @param front/side/rear    迎头/侧向/尾向三档因子
     */
    public static float factorTowards(double dirX, double dirY, double dirZ,
                                      double posX, double posY, double posZ,
                                      double obsX, double obsY, double obsZ,
                                      float front, float side, float rear) {
        double dirLen = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (dirLen < 1.0E-8D) {
            return side;
        }
        double toObsX = obsX - posX;
        double toObsY = obsY - posY;
        double toObsZ = obsZ - posZ;
        double toObsLen = Math.sqrt(toObsX * toObsX + toObsY * toObsY + toObsZ * toObsZ);
        if (toObsLen < 1.0E-8D) {
            return side;
        }
        double dot = clamp((dirX * toObsX + dirY * toObsY + dirZ * toObsZ) / (dirLen * toObsLen), -1.0D, 1.0D);
        double aspectDeg = Math.toDegrees(Math.acos(dot));
        // 与战机 RVP_AspectRcs 同构：0~90° 迎头段 front→side、90~180° 尾向段 side→rear
        double segmentProgress = clamp((aspectDeg <= 90.0D ? aspectDeg : aspectDeg - 90.0D) / 90.0D, 0.0D, 1.0D);
        double eased = Math.pow(Math.sin(segmentProgress * Math.PI / 2.0D), CURVE_EXPONENT);
        return (float) (aspectDeg <= 90.0D
                ? front + (side - front) * eased
                : side + (rear - side) * eased);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
