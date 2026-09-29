package org.ywzj.rvp.client.visual.cookoff;

/** 全车型共用的会话参数；自动适配，无逐车 JSON 和模型 ID 白名单。 */
public final class RVP_WreckCookoffSettings {
    /** 舱盖柱高，单位格，默认 6；调试范围 1～16。 */
    public static double height = 6;
    /** 炮口柱长，单位格，默认 3；调试范围 0.5～8。 */
    public static double length = 3;
    /** 原版火焰数量倍率，无单位，默认 1；调试范围 0～2。 */
    public static double density = 1;
    /** 火柱从最小尺寸增长到完整尺寸的时长，单位 tick，默认 10；调试范围 1～120。 */
    public static double growthTicks = 10;
    /** 残骸结束观察后火柱从当前尺寸收缩到零的时长，单位 tick，默认 20；调试范围 1～120。 */
    public static double shrinkTicks = 20;

    private RVP_WreckCookoffSettings() {}

    /** 重置全局会话调参，不改动任何资源文件。 */
    public static void reset() {
        height = 6;
        length = 3;
        density = 1;
        growthTicks = 10;
        shrinkTicks = 20;
    }
}
