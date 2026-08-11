package org.ywzj.rvp.client.visual.thermobaric;

import net.minecraft.util.Mth;

import java.util.List;

/**
 * 为爆心覆盖层与中心上升层选择稳定锚点，并计算两者之间的派生粒子连接链。
 */
final class RVP_ThermobaricCloudLink {
    /** 单个温压实例允许追加的连接粒子绝对上限。 */
    private static final int MAX_LINK_PARTICLES = 64;
    /** 相邻连接粒子半边长之和相对中心间距的最小安全余量。 */
    private static final double OVERLAP_EPSILON = 1.0E-4D;

    private RVP_ThermobaricCloudLink() {
    }

    /**
     * 从已经按事件种子生成的云团中选择一对固定锚点。
     * 评分优先选择水平角接近、径向位置接近且靠近爆心的组合，同分时使用原始索引保证稳定。
     *
     * @param clouds 当前实例的基础烟云列表
     * @param eventSeed 服务端同步的事件随机种子
     * @return 同时存在爆心覆盖层和中心上升层时返回锚点，否则返回 {@code null}
     */
    static AnchorPair selectAnchors(List<RVP_ThermobaricEffectInstance.Cloud> clouds,
            long eventSeed) {
        int bestCenterIndex = -1;
        int bestUpdraftIndex = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int centerIndex = 0; centerIndex < clouds.size(); centerIndex++) {
            RVP_ThermobaricEffectInstance.Cloud center = clouds.get(centerIndex);
            if (center.layer() != RVP_ThermobaricEffectInstance.CloudLayer.CENTER) {
                continue;
            }
            for (int updraftIndex = 0; updraftIndex < clouds.size(); updraftIndex++) {
                RVP_ThermobaricEffectInstance.Cloud updraft = clouds.get(updraftIndex);
                if (updraft.layer() != RVP_ThermobaricEffectInstance.CloudLayer.UPDRAFT) {
                    continue;
                }
                double angleDistance = Math.abs(Math.IEEEremainder(
                        center.angle() - updraft.angle(), Mth.TWO_PI));
                double radialDistance = Math.abs(center.radialFactor() - updraft.radialFactor());
                double centerBias = (center.radialFactor() + updraft.radialFactor()) * 0.5D;
                double score = angleDistance * 2.0D + radialDistance + centerBias;
                if (score < bestScore - 1.0E-9D
                        || Math.abs(score - bestScore) <= 1.0E-9D
                        && isEarlierPair(centerIndex, updraftIndex,
                        bestCenterIndex, bestUpdraftIndex)) {
                    bestScore = score;
                    bestCenterIndex = centerIndex;
                    bestUpdraftIndex = updraftIndex;
                }
            }
        }
        if (bestCenterIndex < 0 || bestUpdraftIndex < 0) {
            return null;
        }
        long visualSeed = mixSeed(eventSeed, bestCenterIndex, bestUpdraftIndex);
        return new AnchorPair(bestCenterIndex, bestUpdraftIndex, visualSeed);
    }

    /** 根据基础烟云数量计算派生连接粒子的性能上限。 */
    static int resolveParticleLimit(int baseCloudCount) {
        if (baseCloudCount < 2) {
            return 0;
        }
        return Math.min(MAX_LINK_PARTICLES, Math.max(1, baseCloudCount / 4));
    }

    /**
     * 根据两个锚点的实际世界距离和尺寸计算本帧连接链布局。
     * 若端点已经相交则不追加粒子；若达到数量上限，则通过增大粒子尺寸继续保证覆盖。
     */
    static Layout resolveLayout(double distance, float startHalfSize, float endHalfSize,
            float visualRadius, int particleLimit) {
        double safeDistance = Double.isFinite(distance) ? Math.max(0.0D, distance) : 0.0D;
        double safeStartSize = finiteNonNegative(startHalfSize);
        double safeEndSize = finiteNonNegative(endHalfSize);
        double safeVisualRadius = finiteNonNegative(visualRadius);
        if (particleLimit <= 0 || safeVisualRadius <= 0.0D
                || safeDistance <= safeStartSize + safeEndSize) {
            return Layout.EMPTY;
        }
        double preferredSpacing = Math.max(safeVisualRadius * 0.18D,
                Math.min(safeStartSize, safeEndSize) * 1.20D);
        int desiredCount = preferredSpacing > 0.0D
                ? saturatingCeil(safeDistance / preferredSpacing) - 1 : particleLimit;
        int particleCount = Math.min(particleLimit, Math.max(1, desiredCount));
        double spacing = safeDistance / (particleCount + 1.0D);
        return new Layout(particleCount, spacing, safeStartSize, safeEndSize);
    }

    /** 返回指定连接粒子的稳定贴图旋转角。 */
    static float resolveRotation(long visualSeed, int particleIndex) {
        long mixed = mix64(visualSeed + 0x9E3779B97F4A7C15L * (particleIndex + 1L));
        return unitFloat(mixed) * Mth.TWO_PI;
    }

    /** 返回指定连接粒子的稳定亮度倍率。 */
    static float resolveBrightness(long visualSeed, int particleIndex) {
        long mixed = mix64(visualSeed ^ 0xD1B54A32D192ED03L * (particleIndex + 1L));
        return 0.88F + unitFloat(mixed) * 0.18F;
    }

    private static boolean isEarlierPair(int centerIndex, int updraftIndex,
            int bestCenterIndex, int bestUpdraftIndex) {
        return bestCenterIndex < 0 || centerIndex < bestCenterIndex
                || centerIndex == bestCenterIndex && updraftIndex < bestUpdraftIndex;
    }

    private static long mixSeed(long eventSeed, int centerIndex, int updraftIndex) {
        return mix64(eventSeed ^ ((long) centerIndex << 32)
                ^ Integer.toUnsignedLong(updraftIndex) ^ 0xA24BAED4963EE407L);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static float unitFloat(long value) {
        return (value >>> 40) * 0x1.0p-24F;
    }

    private static double finiteNonNegative(float value) {
        return Float.isFinite(value) ? Math.max(0.0F, value) : 0.0D;
    }

    private static int saturatingCeil(double value) {
        if (!Double.isFinite(value) || value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(Math.max(0.0D, value));
    }

    private static float saturatingFloat(double value) {
        return value >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) value;
    }

    /**
     * @param centerIndex 爆心覆盖层锚点在基础烟云列表中的索引
     * @param updraftIndex 中心上升层锚点在基础烟云列表中的索引
     * @param visualSeed 连接粒子旋转与亮度使用的稳定随机种子
     */
    record AnchorPair(int centerIndex, int updraftIndex, long visualSeed) {
    }

    /**
     * @param particleCount 本帧需要追加的连接粒子数量
     * @param spacing 相邻端点或连接粒子中心的世界距离
     * @param startHalfSize 爆心覆盖层锚点的半边长
     * @param endHalfSize 中心上升层锚点的半边长
     */
    record Layout(int particleCount, double spacing,
                  double startHalfSize, double endHalfSize) {
        /** 无需连接粒子时复用的空布局。 */
        private static final Layout EMPTY = new Layout(0, 0.0D, 0.0D, 0.0D);

        /** 返回当前连接粒子在线段上的插值进度。 */
        double progress(int particleIndex) {
            return (particleIndex + 1.0D) / (particleCount + 1.0D);
        }

        /**
         * 返回当前连接粒子的半边长，并保证首尾端点和相邻连接粒子互相覆盖。
         */
        float halfSize(int particleIndex) {
            if (particleIndex < 0 || particleIndex >= particleCount) {
                return 0.0F;
            }
            double progress = progress(particleIndex);
            double interpolatedSize = Mth.lerp(progress, startHalfSize, endHalfSize);
            double requiredSize = spacing * 0.5D + OVERLAP_EPSILON;
            if (particleIndex == 0) {
                requiredSize = Math.max(requiredSize,
                        spacing - startHalfSize + OVERLAP_EPSILON);
            }
            if (particleIndex == particleCount - 1) {
                requiredSize = Math.max(requiredSize,
                        spacing - endHalfSize + OVERLAP_EPSILON);
            }
            return saturatingFloat(Math.max(interpolatedSize, requiredSize));
        }
    }
}
