package org.ywzj.rvp.client.visual.cookoff;

/** 全车型共用的会话参数；自动适配，无逐车 JSON 和模型 ID 白名单。 */
public final class RVP_WreckCookoffSettings {
    /** 车顶喷燃生成点偏置下限，单位格；负值表示向炮塔内部/下方。 */
    public static final double ROOF_VERTICAL_OFFSET_MIN = -4.0D;
    /** 车顶喷燃生成点偏置上限，单位格；正值表示向炮塔外部/上方。 */
    public static final double ROOF_VERTICAL_OFFSET_MAX = 4.0D;
    /** 舱盖柱高，单位格，调试范围 1～16。 */
    public static double height = 9;
    /** 炮口殉燃效果沿炮管的轴向范围，单位格，默认 3；调试范围 0.5～8。 */
    public static double length = 3;
    /** 原版火焰数量倍率，无单位，默认 1；调试范围 0～2。 */
    public static double density = 1;
    /** 火柱从最小尺寸增长到完整尺寸的时长，单位 tick，默认 10；调试范围 1～120。 */
    public static double growthTicks = 10;
    /** 残骸结束观察后火柱从当前尺寸收缩到零的时长，单位 tick，默认 20；调试范围 1～120。 */
    public static double shrinkTicks = 20;
    /** 车顶喷燃轴向速度下限，单位格/tick，调试范围 0.05～20.0。 */
    public static double roofAxisSpeedMin = 0.8D;
    /** 车顶喷燃轴向速度上限，单位格/tick，调试范围 0.05～20.0。 */
    public static double roofAxisSpeedMax = 1.0D;
    /** 车顶倒梯形圆锥根部半径占基础喷燃宽度的比例，默认 0.12；调试范围 0.02～1.5。 */
    public static double roofConeBase = 0.12D;
    /** 车顶倒梯形圆锥远端半径占基础喷燃宽度的比例，默认 0.95；调试范围 0.02～2.5。 */
    public static double roofConeTip = 0.95D;
    /** 车顶喷燃远端最大径向展开速度，单位格/tick，默认 0.11；调试范围 0～0.5。 */
    public static double roofOutwardSpeed = 0.11D;
    /** 车顶喷燃贴图随机倍率下限，无单位，默认 1.0；调试范围 0.1～3.0。 */
    public static double roofTextureScaleMin = 1.0D;
    /** 车顶喷燃贴图随机倍率上限，无单位，默认 3.0；调试范围 0.1～3.0。 */
    public static double roofTextureScaleMax = 3.0D;
    /** 车顶喷燃生成点沿世界竖直方向的偏置，单位格，正值向外/上，负值向内/下。 */
    public static double roofVerticalOffset = -0.5D;
    /** 炮口灰烟生成倍率，无单位，默认 1；调试范围 0～2。 */
    public static double muzzleSmokeDensity = 0.8D;
    /** 炮口灰烟面片尺寸，单位格，默认 0.65；调试范围 0.1～3.0。 */
    public static double muzzleSmokeSize = 0.65D;
    /** 炮口灰烟生命周期，单位 tick，默认 36；调试范围 4～120。 */
    public static double muzzleSmokeLifetime = 36.0D;
    /** 炮口灰烟沿炮管方向的初速度，单位格/tick */
    public static double muzzleSmokeAxisSpeed = 0.18D;
    /** 炮口灰烟目标竖直上浮速度，单位格/tick */
    public static double muzzleSmokeUpdraft = 0.08D;
    /** 炮口灰烟水平摆动速度增量，单位格/tick */
    public static double muzzleSmokeSpread = 0.003D;
    /** 炮口灰烟初始透明度，无单位，默认 0.60；调试范围 0～1。 */
    public static double muzzleSmokeAlpha = 0.60D;
    /** 炮口灰烟灰度，无单位，默认 0.13；0 为黑色，调试范围 0.02～0.5。 */
    public static double muzzleSmokeGrey = 0.13D;

    private RVP_WreckCookoffSettings() {}

    /** 重置全局会话调参，不改动任何资源文件。 */
    public static void reset() {
        height = 9;
        length = 3;
        density = 1;
        growthTicks = 10;
        shrinkTicks = 20;
        roofAxisSpeedMin = 0.8D;
        roofAxisSpeedMax = 1.0D;
        roofConeBase = 0.12D;
        roofConeTip = 0.95D;
        roofOutwardSpeed = 0.11D;
        roofTextureScaleMin = 1.0D;
        roofTextureScaleMax = 3.0D;
        roofVerticalOffset = -0.5D;
        muzzleSmokeDensity = 1.0D;
        muzzleSmokeSize = 0.65D;
        muzzleSmokeLifetime = 36.0D;
        muzzleSmokeAxisSpeed = 0.12D;
        muzzleSmokeUpdraft = 0.045D;
        muzzleSmokeSpread = 0.025D;
        muzzleSmokeAlpha = 0.60D;
        muzzleSmokeGrey = 0.13D;
    }

    /** 设置车顶速度下限；若超过上限则同步抬高上限，保持范围有序。 */
    public static void setRoofAxisSpeedMin(double value) {
        roofAxisSpeedMin = value;
        roofAxisSpeedMax = Math.max(roofAxisSpeedMax, value);
    }

    /** 设置车顶速度上限；若低于下限则同步降低下限，保持范围有序。 */
    public static void setRoofAxisSpeedMax(double value) {
        roofAxisSpeedMax = value;
        roofAxisSpeedMin = Math.min(roofAxisSpeedMin, value);
    }

    /** 设置车顶圆锥根部比例；若超过远端比例则同步扩大远端比例。 */
    public static void setRoofConeBase(double value) {
        roofConeBase = value;
        roofConeTip = Math.max(roofConeTip, value);
    }

    /** 设置车顶圆锥远端比例；若低于根部比例则同步收窄根部比例。 */
    public static void setRoofConeTip(double value) {
        roofConeTip = value;
        roofConeBase = Math.min(roofConeBase, value);
    }

    /** 设置车顶贴图倍率下限；若超过上限则同步抬高上限，保持随机区间有序。 */
    public static void setRoofTextureScaleMin(double value) {
        roofTextureScaleMin = value;
        roofTextureScaleMax = Math.max(roofTextureScaleMax, value);
    }

    /** 设置车顶贴图倍率上限；若低于下限则同步降低下限，保持随机区间有序。 */
    public static void setRoofTextureScaleMax(double value) {
        roofTextureScaleMax = value;
        roofTextureScaleMin = Math.min(roofTextureScaleMin, value);
    }

    /** 设置车顶喷燃生成点竖直偏置；超出调试范围时钳制到安全边界。 */
    public static void setRoofVerticalOffset(double value) {
        roofVerticalOffset = Double.isFinite(value)
                ? Math.max(ROOF_VERTICAL_OFFSET_MIN, Math.min(ROOF_VERTICAL_OFFSET_MAX, value))
                : 0.0D;
    }
}
