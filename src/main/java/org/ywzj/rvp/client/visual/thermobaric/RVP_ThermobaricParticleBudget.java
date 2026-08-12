package org.ywzj.rvp.client.visual.thermobaric;

import java.util.function.IntToDoubleFunction;

/** 实验性温压动态粒子预算的纯面积计算工具。 */
final class RVP_ThermobaricParticleBudget {
    /** 粒子凝结云用于填补球面空隙的覆盖重叠系数。 */
    static final double CONDENSATION_OVERLAP_FACTOR = 2.50D;
    /** 三条尘环带用于保持连续圆周的覆盖重叠系数。 */
    static final double DUST_OVERLAP_FACTOR = 1.20D;
    /** 体积火球用于形成多层饱满外观的覆盖重叠系数。 */
    static final double FIREBALL_OVERLAP_FACTOR = 1.50D;
    /** 不规则后燃云用于填补层间空隙的覆盖重叠系数。 */
    static final double CLOUD_OVERLAP_FACTOR = 3.0D;
    /** 禁用实验功能时复用的空尘环顺序，避免改变旧路径的实例分配。 */
    private static final int[] EMPTY_SEGMENT_ORDER = new int[0];

    private RVP_ThermobaricParticleBudget() {
    }

    /**
     * 按实验开关解析最终绘制数量；关闭时严格退回既有距离 LOD 算法。
     */
    static int resolveRenderCount(boolean enabled, int capacity, float particleRatio,
            int coverageLimitedCount) {
        if (!enabled) {
            // 调用既有温压 LOD 数量算法，确保实验关闭时数量行为完全不变。
            return RVP_ThermobaricParticleLod.resolveRenderCount(capacity, particleRatio);
        }
        int normalizedCount = Math.max(0, Math.min(capacity, coverageLimitedCount));
        // 在覆盖面积预算之后应用既有距离 LOD，保证两个限制都不突破各自上一级数量。
        return RVP_ThermobaricParticleLod.resolveRenderCount(normalizedCount, particleRatio);
    }

    /**
     * 按稳定候选顺序累计实际有效面积，返回满足几何需求所需的最短前缀长度。
     */
    static int resolveCoverageLimitedCount(int capacity, double geometryArea,
            double overlapFactor, IntToDoubleFunction effectiveAreaResolver) {
        if (capacity <= 0 || geometryArea <= 0.0D) {
            return 0;
        }
        if (!Double.isFinite(geometryArea) || !Double.isFinite(overlapFactor)
                || overlapFactor <= 0.0D || effectiveAreaResolver == null) {
            return capacity;
        }
        double requiredArea = geometryArea * overlapFactor;
        if (!Double.isFinite(requiredArea)) {
            return capacity;
        }
        double accumulatedArea = 0.0D;
        for (int index = 0; index < capacity; index++) {
            accumulatedArea = addArea(accumulatedArea,
                    effectiveAreaResolver.applyAsDouble(index));
            if (accumulatedArea >= requiredArea) {
                return index + 1;
            }
        }
        return capacity;
    }

    /**
     * 按后燃云实际选择规则累计覆盖面积：先保留两个连接锚点，再加入原始顺序中的非锚点。
     */
    static int resolveAnchoredCoverageLimitedCount(int capacity, double geometryArea,
            double overlapFactor, int firstAnchorIndex, int secondAnchorIndex,
            IntToDoubleFunction effectiveAreaResolver) {
        if (firstAnchorIndex < 0 || secondAnchorIndex < 0
                || firstAnchorIndex >= capacity || secondAnchorIndex >= capacity
                || firstAnchorIndex == secondAnchorIndex) {
            return resolveCoverageLimitedCount(capacity, geometryArea, overlapFactor,
                    effectiveAreaResolver);
        }
        if (capacity <= 0 || geometryArea <= 0.0D) {
            return 0;
        }
        if (!Double.isFinite(geometryArea) || !Double.isFinite(overlapFactor)
                || overlapFactor <= 0.0D || effectiveAreaResolver == null) {
            return capacity;
        }
        double requiredArea = geometryArea * overlapFactor;
        if (!Double.isFinite(requiredArea)) {
            return capacity;
        }
        double accumulatedArea = addArea(0.0D,
                effectiveAreaResolver.applyAsDouble(firstAnchorIndex));
        accumulatedArea = addArea(accumulatedArea,
                effectiveAreaResolver.applyAsDouble(secondAnchorIndex));
        int selectedCount = 2;
        if (accumulatedArea >= requiredArea) {
            return selectedCount;
        }
        for (int index = 0; index < capacity; index++) {
            if (index == firstAnchorIndex || index == secondAnchorIndex) {
                continue;
            }
            accumulatedArea = addArea(accumulatedArea,
                    effectiveAreaResolver.applyAsDouble(index));
            selectedCount++;
            if (accumulatedArea >= requiredArea) {
                return selectedCount;
            }
        }
        return capacity;
    }

    /** 返回一个 billboard 按贴图 alpha 加权后的世界空间有效面积。 */
    static double resolveBillboardEffectiveArea(float halfSize, float textureCoverage) {
        if (!Float.isFinite(halfSize) || halfSize <= 0.0F
                || !Float.isFinite(textureCoverage) || textureCoverage <= 0.0F) {
            return 0.0D;
        }
        double diameter = (double) halfSize * 2.0D;
        double area = diameter * diameter * textureCoverage;
        return Double.isFinite(area) ? area : Double.MAX_VALUE;
    }

    /** 返回球面或球状火球外包络的面积。 */
    static double resolveSphereArea(float radius, float visibleFraction) {
        if (!Float.isFinite(radius) || radius <= 0.0F
                || !Float.isFinite(visibleFraction) || visibleFraction <= 0.0F) {
            return 0.0D;
        }
        double normalizedVisibleFraction = Math.min(1.0D, visibleFraction);
        return 4.0D * Math.PI * radius * (double) radius * normalizedVisibleFraction;
    }

    /** 返回单条尘环带按其 billboard 直径展开后的环带面积。 */
    static double resolveRingBandArea(float radius, float halfSize) {
        if (!Float.isFinite(radius) || radius <= 0.0F
                || !Float.isFinite(halfSize) || halfSize <= 0.0F) {
            return 0.0D;
        }
        return 2.0D * Math.PI * radius * (2.0D * halfSize);
    }

    /** 返回后燃云能覆盖顶视和侧视两种极端视角的最大椭圆轮廓面积。 */
    static double resolveCloudSilhouetteArea(double horizontalRadius,
            double verticalHalfExtent) {
        if (!Double.isFinite(horizontalRadius) || horizontalRadius <= 0.0D
                || !Double.isFinite(verticalHalfExtent) || verticalHalfExtent <= 0.0D) {
            return 0.0D;
        }
        double area = Math.PI * horizontalRadius
                * Math.max(horizontalRadius, verticalHalfExtent);
        return Double.isFinite(area) ? area : Double.POSITIVE_INFINITY;
    }

    /** 火球和后燃云只在 full tick 之前更新预算采样年龄。 */
    static float resolveFrozenBudgetAge(float age, float fullTick) {
        if (!Float.isFinite(age) || !Float.isFinite(fullTick)) {
            return 0.0F;
        }
        return Math.min(age, fullTick);
    }

    /**
     * 创建二进制反转的渐进环段顺序；任意前缀都优先填充当前最大角度间隙。
     */
    static int[] createProgressiveSegmentOrder(int segmentCount) {
        if (segmentCount <= 0) {
            return EMPTY_SEGMENT_ORDER;
        }
        int bitCount = 0;
        long domainSize = 1L;
        while (domainSize < segmentCount) {
            domainSize <<= 1;
            bitCount++;
        }
        int[] order = new int[segmentCount];
        int outputIndex = 0;
        for (long sourceIndex = 0L; sourceIndex < domainSize && outputIndex < segmentCount;
                sourceIndex++) {
            int reversedIndex = bitCount == 0
                    ? 0
                    : Integer.reverse((int) sourceIndex) >>> (Integer.SIZE - bitCount);
            if (reversedIndex < segmentCount) {
                order[outputIndex++] = reversedIndex;
            }
        }
        return order;
    }

    /** 忽略非法或非正候选面积，并对累计溢出使用饱和值。 */
    private static double addArea(double accumulatedArea, double candidateArea) {
        if (!Double.isFinite(candidateArea) || candidateArea <= 0.0D) {
            return candidateArea == Double.POSITIVE_INFINITY
                    ? Double.MAX_VALUE : accumulatedArea;
        }
        double result = accumulatedArea + candidateArea;
        return Double.isFinite(result) ? result : Double.MAX_VALUE;
    }
}
