package org.ywzj.rvp.client.visual;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.math.BigDecimal;

/** 客户端长程击毁烟的会话级调试参数；重启客户端后恢复代码默认值。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckSmokeDebugSettings {

    private RVP_WreckSmokeDebugSettings() {
    }

    /** 可通过 /rvpwrecksmoke set 调整的参数及其默认值、范围和单位。 */
    public enum Parameter {
        /** 两次长程烟发射的间隔，单位 tick；每轮生成一组核心与外层。 */
        EMIT_PERIOD("emitPeriod", 10, 5, 100, true, "tick"),
        /** 单枚长程烟粒子的寿命，单位 tick。 */
        LIFETIME("lifetime", 460, 60, 1200, true, "tick"),
        /** 单生成点水平错相移动的半径，单位格。 */
        HORIZONTAL_OFFSET("horizontalOffset", 1.0, 0, 16, false, "格"),
        /** 水平错相移动完成一周的周期，单位 tick。 */
        PHASE_PERIOD("phasePeriod", 80, 10, 1200, true, "tick"),
        /** Z 轴相对 X 轴的位置相位差，单位度；默认 90 度。 */
        Z_PHASE("zPhase", 90, -360, 360, false, "度"),
        /** 长程烟核心的初始尺寸倍率，外层尺寸也随之变化。 */
        SIZE_SCALE("sizeScale", 1.75, 0.1, 5, false, "倍"),
        /** 每升高一格增加的初始面片半宽比例。 */
        SIZE_GAIN("sizeGain", 0.05, 0, 0.1, false, "每格"),
        /** 长程烟向上气流的目标速度，单位格/tick。 */
        UPDRAFT("updraft", 0.25, 0.01, 1, false, "格/tick"),
        /** 长程烟在出生高度的基础东向风速，单位格/tick。 */
        EAST_WIND("eastWind", 0.057, 0, 0.5, false, "格/tick"),
        /** 长程烟每升高一格增加的东向风速比例。 */
        WIND_GAIN("windGain", 0.04, 0, 0.1, false, "每格"),
        /** 长程烟水平摆动的速度增量，单位格/tick²。 */
        SWAY("sway", 0.0005, 0, 0.01, false, "格/tick²"),
        /** 长程烟从稀疏帧成团为完整帧所需的 tick 数。 */
        FORMATION_TICKS("formationTicks", 48, 0, 200, true, "tick"),
        /** 长程烟开始透明度淡出的寿命比例，范围 0～0.98。 */
        FADE_START("fadeStart", 0.85, 0, 0.98, false, "寿命比例"),
        /** 长程外层烟相对既有随机尺寸的附加倍率。 */
        OUTER_SIZE_SCALE("outerSizeScale", 1, 0.1, 3, false, "倍"),
        /** 长程核心烟相对既有随机透明度的附加倍率。 */
        CORE_ALPHA_SCALE("coreAlphaScale", 1, 0, 2, false, "倍"),
        /** 长程外层烟相对既有随机透明度的附加倍率。 */
        OUTER_ALPHA_SCALE("outerAlphaScale", 1, 0, 2, false, "倍");

        /** 指令中的参数名称。 */
        private final String commandName;
        /** 启动客户端时的代码默认值。 */
        private final double defaultValue;
        /** 指令允许输入的最小值。 */
        private final double minValue;
        /** 指令允许输入的最大值。 */
        private final double maxValue;
        /** 参数是否必须为整数。 */
        private final boolean integer;
        /** 在指令反馈中展示的单位。 */
        private final String unit;
        /** 当前客户端会话中生效的数值。 */
        private double value;

        Parameter(String commandName, double defaultValue, double minValue, double maxValue,
                  boolean integer, String unit) {
            this.commandName = commandName;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.integer = integer;
            this.unit = unit;
            this.value = defaultValue;
        }

        /** 返回 Brigadier 指令中使用的参数名称。 */
        public String commandName() {
            return commandName;
        }

        /** 返回当前会话中的数值。 */
        public double value() {
            return value;
        }

        /** 返回已经验证为整数的当前数值。 */
        public int intValue() {
            return (int) value;
        }

        /** 返回当前数值的浮点表示，供粒子颜色和面片尺寸计算。 */
        public float floatValue() {
            return (float) value;
        }

        /** 返回简短的单位说明。 */
        public String unit() {
            return unit;
        }

        /** 验证并设置数值；非法输入抛出含有效范围的异常。 */
        public void set(double nextValue) {
            if (!Double.isFinite(nextValue) || nextValue < minValue || nextValue > maxValue
                    || (integer && nextValue != Math.rint(nextValue))) {
                throw new IllegalArgumentException(commandName + " 需要"
                        + (integer ? "整数 " : "数值 ") + format(minValue) + "～" + format(maxValue)
                        + (unit.isEmpty() ? "" : " " + unit));
            }
            this.value = nextValue;
        }

        /** 恢复该参数的代码默认值。 */
        public void reset() {
            this.value = defaultValue;
        }

        /** 以适合聊天栏阅读的格式展示当前值和默认值。 */
        public String describe() {
            return commandName + "=" + format(value) + " " + unit
                    + "（默认 " + format(defaultValue) + "）";
        }

        /** 根据指令名称查找参数，未知名称返回 null 以供指令反馈。 */
        public static Parameter find(String name) {
            for (Parameter parameter : values()) {
                if (parameter.commandName.equals(name)) {
                    return parameter;
                }
            }
            return null;
        }

        /** 去掉无意义的尾随零，避免聊天栏出现过长的浮点表示。 */
        private static String format(double number) {
            return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
    }

    /** 将所有会话级数值恢复为代码默认值。 */
    public static void resetAll() {
        for (Parameter parameter : Parameter.values()) {
            parameter.reset();
        }
    }
}
