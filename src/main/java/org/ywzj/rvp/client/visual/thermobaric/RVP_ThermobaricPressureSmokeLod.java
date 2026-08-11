package org.ywzj.rvp.client.visual.thermobaric;

/** 压力波凝结云墙的稳定距离 LOD 与覆盖补偿计算。 */
final class RVP_ThermobaricPressureSmokeLod {
    /** 该距离内绘制全部已生成白烟云片，单位为格。 */
    private static final double FULL_QUALITY_DISTANCE = 128.0D;
    /** 该距离内使用中等质量，超过后使用远距离质量，单位为格。 */
    private static final double MEDIUM_QUALITY_DISTANCE = 256.0D;
    /** 中距离保留的白烟云片比例。 */
    private static final float MEDIUM_QUALITY_RATIO = 0.625F;
    /** 远距离保留的白烟云片比例。 */
    private static final float FAR_QUALITY_RATIO = 0.375F;
    /** 任一可见压力波至少保留的白烟点缀数量。 */
    private static final int MINIMUM_VISIBLE_SMOKE = 12;

    private RVP_ThermobaricPressureSmokeLod() {
    }

    /**
     * 按爆心到相机的距离选择稳定前缀数量；实例列表已按独立随机样本排序，
     * 因此质量切换只增减同一组云片，不会每帧随机换点闪烁。
     */
    static int resolveRenderCount(int generatedCount, double distanceSquared) {
        if (generatedCount <= 0) {
            return 0;
        }
        float ratio;
        if (distanceSquared <= FULL_QUALITY_DISTANCE * FULL_QUALITY_DISTANCE) {
            ratio = 1.0F;
        } else if (distanceSquared <= MEDIUM_QUALITY_DISTANCE * MEDIUM_QUALITY_DISTANCE) {
            ratio = MEDIUM_QUALITY_RATIO;
        } else {
            ratio = FAR_QUALITY_RATIO;
        }
        int minimum = Math.min(MINIMUM_VISIBLE_SMOKE, generatedCount);
        return Math.max(minimum, Math.min(generatedCount, Math.round(generatedCount * ratio)));
    }

}
