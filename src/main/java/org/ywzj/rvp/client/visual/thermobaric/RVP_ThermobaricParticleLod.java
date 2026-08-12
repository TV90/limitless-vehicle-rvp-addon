package org.ywzj.rvp.client.visual.thermobaric;

/** 温压火球、凝结云、尘环与后燃烟云共用的粒子数量 LOD 计算。 */
final class RVP_ThermobaricParticleLod {
    private RVP_ThermobaricParticleLod() {
    }

    /**
     * 按保留比例计算当前档位的粒子数量；正比例至少保留一个，且绝不超过已生成数量。
     */
    static int resolveRenderCount(int generatedCount, float particleRatio) {
        if (generatedCount <= 0 || !Float.isFinite(particleRatio) || particleRatio <= 0.0F) {
            return 0;
        }
        if (particleRatio >= 1.0F) {
            return generatedCount;
        }
        double scaledCount = Math.ceil(generatedCount * (double) particleRatio);
        return Math.max(1, Math.min(generatedCount, (int) scaledCount));
    }

    /** 固定三层火球核心也按同一保留比例降级。 */
    static int resolveCoreLayerCount(float particleRatio) {
        return resolveRenderCount(3, particleRatio);
    }

    /**
     * 从完整尘环中近似均匀地选取一个原始环段索引，避免 LOD 后只剩连续局部圆弧。
     */
    static int resolveEvenlySpacedIndex(int renderIndex, int renderCount, int generatedCount) {
        if (renderIndex < 0 || renderIndex >= renderCount || renderCount <= 0
                || generatedCount <= 0 || renderCount > generatedCount) {
            return -1;
        }
        double centeredSample = (renderIndex + 0.5D) * generatedCount / renderCount;
        return Math.min(generatedCount - 1, (int) Math.floor(centeredSample));
    }
}
