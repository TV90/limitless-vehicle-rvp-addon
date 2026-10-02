package org.ywzj.rvp.client.visual.vehicle;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * [RVP] 大口径延迟炮口烟的客户端会话参数。
 *
 * <p>参数只通过 {@code /rvpmuzzlesmoke} 修改，不写入配置文件，也不经网络同步；客户端
 * 重启后恢复默认值。大口径烟的管理器独立使用本类，避免污染现有小口径炮口烟参数。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_DelayedMuzzleSmokeSettings {

    private RVP_DelayedMuzzleSmokeSettings() {
    }

    /** 参数配对组标识，防止任意可调范围的最小值大于最大值。 */
    private enum Pair {
        DELAY,
        EMIT_DURATION,
        PARTICLE_LIFETIME,
        AXIAL_SPEED
    }

    /** 可由客户端指令调整的延迟炮口烟 tick 参数。 */
    public enum Parameter {
        /** 开火到第一枚延迟烟生成的最小等待时间，单位 tick。 */
        DELAY_MIN("delayMin", 10, 0, 600, "tick", Pair.DELAY),
        /** 开火到第一枚延迟烟生成的最大等待时间，单位 tick。 */
        DELAY_MAX("delayMax", 20, 0, 600, "tick", Pair.DELAY),
        /** 延迟烟连续喷出的最短时长，单位 tick。 */
        EMIT_DURATION_MIN("emitDurationMin", 15, 1, 600, "tick", Pair.EMIT_DURATION),
        /** 延迟烟连续喷出的最长时长，单位 tick。 */
        EMIT_DURATION_MAX("emitDurationMax", 20, 1, 600, "tick", Pair.EMIT_DURATION),
        /** 单枚白色延迟烟粒子的最短寿命，单位 tick。 */
        PARTICLE_LIFETIME_MIN("particleLifetimeMin", 40, 1, 1200, "tick", Pair.PARTICLE_LIFETIME),
        /** 单枚白色延迟烟粒子的最长寿命，单位 tick。 */
        PARTICLE_LIFETIME_MAX("particleLifetimeMax", 60, 1, 1200, "tick", Pair.PARTICLE_LIFETIME);

        /** 指令使用的参数名称。 */
        private final String commandName;
        /** 客户端启动时使用的默认值。 */
        private final int defaultValue;
        /** 指令允许输入的最小值。 */
        private final int minValue;
        /** 指令允许输入的最大值。 */
        private final int maxValue;
        /** 聊天反馈中的单位。 */
        private final String unit;
        /** 需要保持最小值不大于最大值的参数组。 */
        private final Pair pair;
        /** 当前客户端会话中生效的值。 */
        private int value;

        Parameter(String commandName, int defaultValue, int minValue, int maxValue,
                  String unit, Pair pair) {
            this.commandName = commandName;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.unit = unit;
            this.pair = pair;
            this.value = defaultValue;
        }

        /** 返回指令中的参数名。 */
        public String commandName() {
            return commandName;
        }

        /** 返回当前会话值。 */
        public int intValue() {
            return value;
        }

        /** 返回当前值、单位和代码默认值。 */
        public String describe() {
            return commandName + "=" + value + " " + unit + "（默认 " + defaultValue + "）";
        }

        /** 验证单个参数的数值范围。 */
        private void validateValue(double nextValue) {
            if (!Double.isFinite(nextValue) || nextValue != Math.rint(nextValue)
                    || nextValue < minValue || nextValue > maxValue) {
                throw new IllegalArgumentException(commandName + " 需要整数 "
                        + minValue + "～" + maxValue + " " + unit);
            }
        }

        /** 返回当前参数组的配对参数。 */
        private Parameter peer() {
            return switch (pair) {
                case DELAY -> this == DELAY_MIN ? DELAY_MAX : DELAY_MIN;
                case EMIT_DURATION -> this == EMIT_DURATION_MIN
                        ? EMIT_DURATION_MAX : EMIT_DURATION_MIN;
                case PARTICLE_LIFETIME -> this == PARTICLE_LIFETIME_MIN
                        ? PARTICLE_LIFETIME_MAX : PARTICLE_LIFETIME_MIN;
                case AXIAL_SPEED -> throw new IllegalStateException("tick 参数不能使用速度参数组");
            };
        }

        /** 按指令名称查找参数，找不到时返回 null。 */
        public static Parameter find(String name) {
            for (Parameter parameter : values()) {
                if (parameter.commandName.equals(name)) {
                    return parameter;
                }
            }
            return null;
        }

    }

    /** 可由客户端指令调整的延迟烟出膛方向初速度参数，单位为格/tick。 */
    public enum VelocityParameter {
        /** 白烟沿当前炮管出膛方向的最小初速度，单位格/tick。 */
        AXIAL_SPEED_MIN("axialSpeedMin", 0.12, 0.0, 0.2, "格/tick", Pair.AXIAL_SPEED),
        /** 白烟沿当前炮管出膛方向的最大初速度，单位格/tick。 */
        AXIAL_SPEED_MAX("axialSpeedMax", 0.15, 0.0, 0.2, "格/tick", Pair.AXIAL_SPEED);

        /** 指令使用的速度参数名称。 */
        private final String commandName;
        /** 客户端启动时使用的速度默认值。 */
        private final double defaultValue;
        /** 指令允许输入的速度最小值。 */
        private final double minValue;
        /** 指令允许输入的速度最大值。 */
        private final double maxValue;
        /** 聊天反馈中的速度单位。 */
        private final String unit;
        /** 需要保持最小值不大于最大值的速度参数组。 */
        private final Pair pair;
        /** 当前客户端会话中生效的速度值。 */
        private double value;

        VelocityParameter(String commandName, double defaultValue, double minValue,
                          double maxValue, String unit, Pair pair) {
            this.commandName = commandName;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.unit = unit;
            this.pair = pair;
            this.value = defaultValue;
        }

        /** 返回指令中的速度参数名。 */
        public String commandName() {
            return commandName;
        }

        /** 返回当前速度值。 */
        public double doubleValue() {
            return value;
        }

        /** 返回当前速度值、单位和代码默认值。 */
        public String describe() {
            return commandName + "=" + format(value) + " " + unit
                    + "（默认 " + format(defaultValue) + "）";
        }

        /** 验证单个速度参数的范围。 */
        private void validateValue(double nextValue) {
            if (!Double.isFinite(nextValue) || nextValue < minValue || nextValue > maxValue) {
                throw new IllegalArgumentException(commandName + " 需要数值 "
                        + format(minValue) + "～" + format(maxValue) + " " + unit);
            }
        }

        /** 返回当前速度参数组的配对参数。 */
        private VelocityParameter peer() {
            return switch (pair) {
                case AXIAL_SPEED -> this == AXIAL_SPEED_MIN ? AXIAL_SPEED_MAX : AXIAL_SPEED_MIN;
                default -> throw new IllegalStateException("未知速度参数组: " + pair);
            };
        }

        /** 按指令名称查找速度参数，找不到时返回 null。 */
        public static VelocityParameter find(String name) {
            for (VelocityParameter parameter : values()) {
                if (parameter.commandName.equals(name)) {
                    return parameter;
                }
            }
            return null;
        }
    }

    /** 验证并设置参数，同时保持同组最小值不大于最大值。 */
    public static void set(Parameter parameter, double nextValue) {
        if (parameter == null) {
            throw new IllegalArgumentException("参数不能为空");
        }
        parameter.validateValue(nextValue);
        int next = (int) nextValue;
        Parameter peer = parameter.peer();
        boolean settingMinimum = parameter.name().endsWith("MIN");
        if ((settingMinimum && next > peer.value) || (!settingMinimum && next < peer.value)) {
            throw new IllegalArgumentException(parameter.commandName + " 不能破坏同组最小值/最大值关系；当前配对值为 "
                    + peer.value + " " + parameter.unit);
        }
        parameter.value = next;
    }

    /** 恢复单个参数；若默认值会破坏配对关系，同时恢复该组的配对参数。 */
    public static void reset(Parameter parameter) {
        if (parameter == null) {
            return;
        }
        Parameter peer = parameter.peer();
        boolean settingMinimum = parameter.name().endsWith("MIN");
        if ((settingMinimum && parameter.defaultValue > peer.value)
                || (!settingMinimum && parameter.defaultValue < peer.value)) {
            peer.value = peer.defaultValue;
        }
        parameter.value = parameter.defaultValue;
    }

    /** 恢复所有延迟炮口烟会话参数。 */
    public static void resetAll() {
        for (Parameter parameter : Parameter.values()) {
            parameter.value = parameter.defaultValue;
        }
        for (VelocityParameter parameter : VelocityParameter.values()) {
            parameter.value = parameter.defaultValue;
        }
    }

    /** 在测试和管理器中生成包含两端的随机整数。 */
    public static int randomInclusive(Parameter min, Parameter max, net.minecraft.util.RandomSource random) {
        int lower = min.intValue();
        int upper = max.intValue();
        return lower + random.nextInt(upper - lower + 1);
    }

    /** 在测试和管理器中按当前范围生成随机初速度，单位为格/tick。 */
    public static double randomDoubleInclusive(VelocityParameter min, VelocityParameter max,
                                               net.minecraft.util.RandomSource random) {
        double lower = min.doubleValue();
        double upper = max.doubleValue();
        return lower + random.nextDouble() * (upper - lower);
    }

    /** 验证并设置速度参数，同时保持同组最小值不大于最大值。 */
    public static void set(VelocityParameter parameter, double nextValue) {
        if (parameter == null) {
            throw new IllegalArgumentException("速度参数不能为空");
        }
        parameter.validateValue(nextValue);
        VelocityParameter peer = parameter.peer();
        boolean settingMinimum = parameter == VelocityParameter.AXIAL_SPEED_MIN;
        if ((settingMinimum && nextValue > peer.value)
                || (!settingMinimum && nextValue < peer.value)) {
            throw new IllegalArgumentException(parameter.commandName
                    + " 不能破坏同组最小值/最大值关系；当前配对值为 "
                    + format(peer.value) + " " + parameter.unit);
        }
        parameter.value = nextValue;
    }

    /** 恢复单个速度参数的默认值，必要时同步恢复配对参数。 */
    public static void reset(VelocityParameter parameter) {
        if (parameter == null) {
            return;
        }
        VelocityParameter peer = parameter.peer();
        boolean settingMinimum = parameter == VelocityParameter.AXIAL_SPEED_MIN;
        if ((settingMinimum && parameter.defaultValue > peer.value)
                || (!settingMinimum && parameter.defaultValue < peer.value)) {
            peer.value = peer.defaultValue;
        }
        parameter.value = parameter.defaultValue;
    }

    /** 将速度值格式化为稳定的指令反馈文本。 */
    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
