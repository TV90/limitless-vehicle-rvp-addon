package org.ywzj.rvp.weapon.visual;

import java.util.Locale;

/**
 * {@code rvp_rocket_flame} 的客户端会话级运行时调参。
 *
 * <p>本类只保存基础数值和覆盖状态，不引用任何客户端类，因此公共弹体代码在服务端加载时安全。
 * 客户端命令负责写入覆盖值；没有覆盖的项目继续使用武器 JSON 或粒子类默认值。</p>
 */
public final class RVP_RocketFlameRuntimeTuning {

    /** 单个尾迹粒子的默认尺寸倍率。 */
    public static final float DEFAULT_PARTICLE_SCALE = 1.0f;
    /** 尾迹路径采样间距的默认值，单位格。 */
    public static final float DEFAULT_STEP = 0.5f;
    /** 尾迹生成间隔的默认值，单位 Tick。 */
    public static final int DEFAULT_SPAWN_INTERVAL = 1;
    /** 尾迹采样密度倍率的默认值。 */
    public static final float DEFAULT_DENSITY_SCALE = 1.0f;
    /** 尾迹相对弹体尾部偏移的默认值，单位格。 */
    public static final float DEFAULT_OFFSET = 3.0f;
    /** 发射段加粗倍率的默认值。 */
    public static final float DEFAULT_LAUNCH_BOOST = 1.0f;
    /** 凝结云保持期保底时长，单位 Tick。 */
    public static final int DEFAULT_HOLD_MIN_TICKS = 108;
    /** 发动机燃尽后的保持缓冲时长，单位 Tick。 */
    public static final int DEFAULT_HOLD_BUFFER_TICKS = 20;
    /** 凝结云燃尽后的最短淡出时长，单位 Tick。 */
    public static final int DEFAULT_FADE_TICKS = 120;
    /** 凝结云淡出时长的随机增量上限，单位 Tick。 */
    public static final int DEFAULT_FADE_RANDOM_TICKS = 60;
    /** 固体发动机橙焰相位的最短时长，单位 Tick。 */
    public static final int DEFAULT_FLAME_TICKS = 10;
    /** 固体发动机橙焰相位的随机增量上限，单位 Tick。 */
    public static final int DEFAULT_FLAME_RANDOM_TICKS = 2;
    /** 固体发动机烟相位的出生灰度下限。 */
    public static final float DEFAULT_SMOKE_GREY_MIN = 0.6f;
    /** 固体发动机烟相位的灰度随机幅度。 */
    public static final float DEFAULT_SMOKE_GREY_SPREAD = 0.01f;
    /** 近距离尾迹的默认抖动 quad 层数。 */
    public static final int DEFAULT_TRAIL_LAYERS = 3;
    /** 尾迹抖动重掷间隔下限，单位 Tick。 */
    public static final int DEFAULT_JITTER_MIN_TICKS = 40;
    /** 尾迹抖动重掷间隔上限，单位 Tick。 */
    public static final int DEFAULT_JITTER_MAX_TICKS = 60;
    /** 尾迹抖动幅度随寿命衰减的默认比例。 */
    public static final float DEFAULT_TURBULENCE_DECAY = 0.65f;
    /** 尾迹层在水平 X/Z 轴上的默认抖动幅度，单位为高斯随机值倍率。 */
    public static final float DEFAULT_JITTER_HORIZONTAL_SCALE = 0.2f;
    /** 尾迹层在垂直 Y 轴上的默认抖动幅度，单位为高斯随机值倍率。 */
    public static final float DEFAULT_JITTER_VERTICAL_SCALE = 0.5f;
    /** 尾迹层随粒子年龄扩散的默认增长系数。 */
    public static final float DEFAULT_JITTER_SPREAD_SCALE = 1.5f;
    /** 尾迹抖动插值平滑度的默认值；1 为 smoothstep，0 为线性插值。 */
    public static final float DEFAULT_JITTER_INTERPOLATION = 1.0f;
    /** 单个粒子随机旋转的默认最大角度，单位度；0 表示保持当前朝向。 */
    public static final float DEFAULT_ROTATION_RANDOM_DEGREES = 180.0f;
    /** 抖动持续时间占粒子生命周期的默认比例；0 表示不启用额外截止。 */
    public static final float DEFAULT_JITTER_DURATION_RATIO = 0.0f;

    /** 单个尾迹粒子尺寸倍率的临时覆盖值；为空表示跟随武器 JSON。 */
    private static volatile Float particleScaleOverride;
    /** 尾迹采样间距的临时覆盖值；为空表示跟随武器 JSON。 */
    private static volatile Float stepOverride;
    /** 尾迹生成间隔的临时覆盖值；为空表示跟随武器 JSON。 */
    private static volatile Integer spawnIntervalOverride;
    /** 尾迹密度倍率的临时覆盖值；为空表示跟随武器 JSON。 */
    private static volatile Float densityScaleOverride;
    /** 尾迹生成点偏移的临时覆盖值；为空表示跟随武器 JSON。 */
    private static volatile Float offsetOverride;
    /** 发射段加粗倍率的临时覆盖值；为空表示跟随武器 JSON。 */
    private static volatile Float launchBoostOverride;
    /** 是否启用尾迹的临时覆盖值；为空表示跟随武器 JSON。 */
    private static volatile Boolean enabledOverride;
    /** 是否启用贴地烟浪的临时覆盖值；为空表示跟随风格默认值或武器 JSON。 */
    private static volatile Boolean groundWashOverride;
    /** 凝结云保持期保底时长的临时覆盖值，单位 Tick。 */
    private static volatile Integer holdMinTicksOverride;
    /** 燃尽后保持缓冲时长的临时覆盖值，单位 Tick。 */
    private static volatile Integer holdBufferTicksOverride;
    /** 燃尽后最短淡出时长的临时覆盖值，单位 Tick。 */
    private static volatile Integer fadeTicksOverride;
    /** 淡出随机增量上限的临时覆盖值，单位 Tick。 */
    private static volatile Integer fadeRandomTicksOverride;
    /** 橙焰相位最短时长的临时覆盖值，单位 Tick。 */
    private static volatile Integer flameTicksOverride;
    /** 橙焰相位随机增量上限的临时覆盖值，单位 Tick。 */
    private static volatile Integer flameRandomTicksOverride;
    /** 固体发动机烟相位出生灰度下限的临时覆盖值。 */
    private static volatile Float smokeGreyMinOverride;
    /** 固体发动机烟相位灰度随机幅度的临时覆盖值。 */
    private static volatile Float smokeGreySpreadOverride;
    /** 近距离尾迹抖动 quad 层数的临时覆盖值。 */
    private static volatile Integer trailLayersOverride;
    /** 尾迹抖动重掷间隔下限的临时覆盖值，单位 Tick。 */
    private static volatile Integer jitterMinTicksOverride;
    /** 尾迹抖动重掷间隔上限的临时覆盖值，单位 Tick。 */
    private static volatile Integer jitterMaxTicksOverride;
    /** 尾迹抖动幅度随寿命衰减比例的临时覆盖值。 */
    private static volatile Float turbulenceDecayOverride;
    /** 尾迹层水平 X/Z 轴抖动幅度的临时覆盖值。 */
    private static volatile Float jitterHorizontalScaleOverride;
    /** 尾迹层垂直 Y 轴抖动幅度的临时覆盖值。 */
    private static volatile Float jitterVerticalScaleOverride;
    /** 尾迹层随粒子年龄扩散增长系数的临时覆盖值。 */
    private static volatile Float jitterSpreadScaleOverride;
    /** 尾迹抖动插值平滑度的临时覆盖值。 */
    private static volatile Float jitterInterpolationOverride;
    /** 单个粒子随机旋转最大角度的临时覆盖值，单位度。 */
    private static volatile Float rotationRandomDegreesOverride;
    /** 抖动持续时间比例的临时覆盖值；0 表示不启用额外截止。 */
    private static volatile Float jitterDurationRatioOverride;

    /** 可由游戏内调参命令设置的参数及其显示名称。 */
    public enum Parameter {
        /** 单个粒子尺寸倍率。 */
        PARTICLE_SCALE("particleScale", "单个粒子尺寸倍率"),
        /** 尾迹路径采样间距。 */
        STEP("step", "路径采样间距（格）"),
        /** 尾迹生成间隔。 */
        SPAWN_INTERVAL("spawnInterval", "生成间隔（Tick）"),
        /** 尾迹采样密度倍率。 */
        DENSITY_SCALE("densityScale", "采样密度倍率"),
        /** 尾迹相对弹体尾部偏移。 */
        OFFSET("offset", "尾部偏移（格）"),
        /** 发射段尾迹加粗倍率。 */
        LAUNCH_BOOST("launchBoost", "发射段加粗倍率"),
        /** 总尾迹开关。 */
        ENABLED("enabled", "原生尾迹开关"),
        /** 贴地烟浪开关。 */
        GROUND_WASH("groundWash", "贴地烟浪开关"),
        /** 凝结云保持期保底。 */
        HOLD_MIN_TICKS("holdMinTicks", "保持期保底（Tick）"),
        /** 燃尽后保持缓冲。 */
        HOLD_BUFFER_TICKS("holdBufferTicks", "燃尽后缓冲（Tick）"),
        /** 燃尽后最短淡出时长。 */
        FADE_TICKS("fadeTicks", "淡出基础时长（Tick）"),
        /** 淡出随机增量上限。 */
        FADE_RANDOM_TICKS("fadeRandomTicks", "淡出随机增量（Tick）"),
        /** 橙焰相位最短时长。 */
        FLAME_TICKS("flameTicks", "橙焰基础时长（Tick）"),
        /** 橙焰相位随机增量上限。 */
        FLAME_RANDOM_TICKS("flameRandomTicks", "橙焰随机增量（Tick）"),
        /** 烟相位出生灰度下限。 */
        SMOKE_GREY_MIN("smokeGreyMin", "烟相位灰度下限"),
        /** 烟相位灰度随机幅度。 */
        SMOKE_GREY_SPREAD("smokeGreySpread", "烟相位灰度幅度"),
        /** 近距离尾迹层数。 */
        TRAIL_LAYERS("trailLayers", "近距离尾迹层数"),
        /** 抖动重掷间隔下限。 */
        JITTER_MIN_TICKS("jitterMinTicks", "抖动间隔下限（Tick）"),
        /** 抖动重掷间隔上限。 */
        JITTER_MAX_TICKS("jitterMaxTicks", "抖动间隔上限（Tick）"),
        /** 抖动幅度衰减比例。 */
        TURBULENCE_DECAY("turbulenceDecay", "抖动幅度衰减比例"),
        /** 水平 X/Z 轴抖动幅度。 */
        JITTER_HORIZONTAL_SCALE("jitterHorizontalScale", "水平抖动幅度"),
        /** 垂直 Y 轴抖动幅度。 */
        JITTER_VERTICAL_SCALE("jitterVerticalScale", "垂直抖动幅度"),
        /** 随年龄扩散的抖动增长系数。 */
        JITTER_SPREAD_SCALE("jitterSpreadScale", "年龄扩散增长系数"),
        /** 抖动插值平滑度，0 为线性、1 为 smoothstep。 */
        JITTER_INTERPOLATION("jitterInterpolation", "抖动插值平滑度"),
        /** 单个粒子随机旋转最大角度，实际角度在正负范围内随机。 */
        ROTATION_RANDOM_DEGREES("rotationRandomDegrees", "随机旋转角度范围（度）"),
        /** 抖动持续时间占粒子生命周期的比例，0 表示不启用额外截止。 */
        JITTER_DURATION_RATIO("jitterDurationRatio", "抖动持续时间比例");

        /** 指令中使用的参数名。 */
        private final String commandName;
        /** 聊天栏显示的参数说明。 */
        private final String description;

        Parameter(String commandName, String description) {
            this.commandName = commandName;
            this.description = description;
        }

        /** 返回指令参数名。 */
        public String commandName() {
            return commandName;
        }

        /** 按不区分大小写的指令参数名查找枚举值。 */
        public static Parameter find(String name) {
            for (Parameter parameter : values()) {
                if (parameter.commandName.equalsIgnoreCase(name)) {
                    return parameter;
                }
            }
            return null;
        }
    }

    private RVP_RocketFlameRuntimeTuning() {
    }

    /** 按当前会话覆盖值解析单个粒子尺寸。 */
    public static float resolveParticleScale(float configured) {
        return particleScaleOverride == null ? configured : particleScaleOverride;
    }

    /** 按当前会话覆盖值解析尾迹采样间距。 */
    public static float resolveStep(float configured) {
        return stepOverride == null ? configured : stepOverride;
    }

    /** 按当前会话覆盖值解析尾迹生成间隔。 */
    public static int resolveSpawnInterval(int configured) {
        return spawnIntervalOverride == null ? configured : spawnIntervalOverride;
    }

    /** 按当前会话覆盖值解析尾迹密度倍率。 */
    public static float resolveDensityScale(float configured) {
        return densityScaleOverride == null ? configured : densityScaleOverride;
    }

    /** 按当前会话覆盖值解析尾迹生成点偏移。 */
    public static float resolveOffset(float configured) {
        return offsetOverride == null ? configured : offsetOverride;
    }

    /** 按当前会话覆盖值解析发射段加粗倍率。 */
    public static float resolveLaunchBoost(float configured) {
        return launchBoostOverride == null ? configured : launchBoostOverride;
    }

    /** 按当前会话覆盖值解析原生尾迹开关。 */
    public static boolean resolveEnabled(boolean configured) {
        return enabledOverride == null ? configured : enabledOverride;
    }

    /** 按当前会话覆盖值解析贴地烟浪开关。 */
    public static boolean resolveGroundWash(boolean configured) {
        return groundWashOverride == null ? configured : groundWashOverride;
    }

    /** 返回当前生效的凝结云保持期保底时长。 */
    public static int resolveHoldMinTicks() {
        return holdMinTicksOverride == null ? DEFAULT_HOLD_MIN_TICKS : holdMinTicksOverride;
    }

    /** 返回当前生效的燃尽后保持缓冲时长。 */
    public static int resolveHoldBufferTicks() {
        return holdBufferTicksOverride == null ? DEFAULT_HOLD_BUFFER_TICKS : holdBufferTicksOverride;
    }

    /** 返回当前生效的燃尽后最短淡出时长。 */
    public static int resolveFadeTicks() {
        return fadeTicksOverride == null ? DEFAULT_FADE_TICKS : fadeTicksOverride;
    }

    /** 返回当前生效的淡出随机增量上限。 */
    public static int resolveFadeRandomTicks() {
        return fadeRandomTicksOverride == null ? DEFAULT_FADE_RANDOM_TICKS : fadeRandomTicksOverride;
    }

    /** 返回当前生效的橙焰相位最短时长。 */
    public static int resolveFlameTicks() {
        return flameTicksOverride == null ? DEFAULT_FLAME_TICKS : flameTicksOverride;
    }

    /** 返回当前生效的橙焰相位随机增量上限。 */
    public static int resolveFlameRandomTicks() {
        return flameRandomTicksOverride == null ? DEFAULT_FLAME_RANDOM_TICKS : flameRandomTicksOverride;
    }

    /** 返回当前生效的固体发动机烟相位出生灰度下限。 */
    public static float resolveSmokeGreyMin() {
        return smokeGreyMinOverride == null ? DEFAULT_SMOKE_GREY_MIN : smokeGreyMinOverride;
    }

    /** 返回当前生效的固体发动机烟相位灰度随机幅度。 */
    public static float resolveSmokeGreySpread() {
        return smokeGreySpreadOverride == null ? DEFAULT_SMOKE_GREY_SPREAD : smokeGreySpreadOverride;
    }

    /** 返回当前生效的近距离尾迹层数。 */
    public static int resolveTrailLayers() {
        return trailLayersOverride == null ? DEFAULT_TRAIL_LAYERS : trailLayersOverride;
    }

    /** 返回当前生效的抖动间隔下限。 */
    public static int resolveJitterMinTicks() {
        return jitterMinTicksOverride == null ? DEFAULT_JITTER_MIN_TICKS : jitterMinTicksOverride;
    }

    /** 返回当前生效的抖动间隔上限。 */
    public static int resolveJitterMaxTicks() {
        return jitterMaxTicksOverride == null ? DEFAULT_JITTER_MAX_TICKS : jitterMaxTicksOverride;
    }

    /** 返回当前生效的抖动幅度衰减比例。 */
    public static float resolveTurbulenceDecay() {
        return turbulenceDecayOverride == null ? DEFAULT_TURBULENCE_DECAY : turbulenceDecayOverride;
    }

    /** 返回当前生效的水平 X/Z 轴抖动幅度。 */
    public static float resolveJitterHorizontalScale() {
        return jitterHorizontalScaleOverride == null
                ? DEFAULT_JITTER_HORIZONTAL_SCALE : jitterHorizontalScaleOverride;
    }

    /** 返回当前生效的垂直 Y 轴抖动幅度。 */
    public static float resolveJitterVerticalScale() {
        return jitterVerticalScaleOverride == null
                ? DEFAULT_JITTER_VERTICAL_SCALE : jitterVerticalScaleOverride;
    }

    /** 返回当前生效的随年龄扩散抖动增长系数。 */
    public static float resolveJitterSpreadScale() {
        return jitterSpreadScaleOverride == null
                ? DEFAULT_JITTER_SPREAD_SCALE : jitterSpreadScaleOverride;
    }

    /** 返回当前生效的抖动插值平滑度。 */
    public static float resolveJitterInterpolation() {
        return jitterInterpolationOverride == null
                ? DEFAULT_JITTER_INTERPOLATION : jitterInterpolationOverride;
    }

    /** 返回当前生效的单粒子随机旋转最大角度。 */
    public static float resolveRotationRandomDegrees() {
        return rotationRandomDegreesOverride == null
                ? DEFAULT_ROTATION_RANDOM_DEGREES : rotationRandomDegreesOverride;
    }

    /** 返回当前生效的抖动持续时间比例。 */
    public static float resolveJitterDurationRatio() {
        return jitterDurationRatioOverride == null
                ? DEFAULT_JITTER_DURATION_RATIO : jitterDurationRatioOverride;
    }

    /** 修改一个运行时参数，并在输入非法时抛出可直接显示给玩家的异常。 */
    public static void set(Parameter parameter, String rawValue) {
        if (parameter == Parameter.ENABLED || parameter == Parameter.GROUND_WASH) {
            boolean value = parseBoolean(rawValue);
            if (parameter == Parameter.ENABLED) {
                enabledOverride = value;
            } else {
                groundWashOverride = value;
            }
            return;
        }

        double value = parseFiniteDouble(rawValue);
        switch (parameter) {
            case PARTICLE_SCALE -> particleScaleOverride = finiteFloat(value, 0.0f, 8.0f, parameter);
            case STEP -> stepOverride = finiteFloat(value, 0.05f, 64.0f, parameter);
            case SPAWN_INTERVAL -> spawnIntervalOverride = integerValue(value, 1, 20, parameter);
            case DENSITY_SCALE -> densityScaleOverride = finiteFloat(value, 0.0f, 8.0f, parameter);
            case OFFSET -> offsetOverride = finiteFloat(value, 0.0f, 64.0f, parameter);
            case LAUNCH_BOOST -> launchBoostOverride = finiteFloat(value, 0.0f, 8.0f, parameter);
            case HOLD_MIN_TICKS -> holdMinTicksOverride = integerValue(value, 0, 2000, parameter);
            case HOLD_BUFFER_TICKS -> holdBufferTicksOverride = integerValue(value, 0, 200, parameter);
            case FADE_TICKS -> fadeTicksOverride = integerValue(value, 0, 2000, parameter);
            case FADE_RANDOM_TICKS -> fadeRandomTicksOverride = integerValue(value, 0, 600, parameter);
            case FLAME_TICKS -> flameTicksOverride = integerValue(value, 0, 600, parameter);
            case FLAME_RANDOM_TICKS -> flameRandomTicksOverride = integerValue(value, 0, 600, parameter);
            case SMOKE_GREY_MIN -> {
                float parsed = finiteFloat(value, 0.0f, 1.0f, parameter);
                if (parsed + resolveSmokeGreySpread() > 1.0f) {
                    throw new IllegalArgumentException("smokeGreyMin + smokeGreySpread 不能大于 1.0");
                }
                smokeGreyMinOverride = parsed;
            }
            case SMOKE_GREY_SPREAD -> {
                float parsed = finiteFloat(value, 0.0f, 1.0f, parameter);
                if (resolveSmokeGreyMin() + parsed > 1.0f) {
                    throw new IllegalArgumentException("smokeGreyMin + smokeGreySpread 不能大于 1.0");
                }
                smokeGreySpreadOverride = parsed;
            }
            case TRAIL_LAYERS -> trailLayersOverride = integerValue(value, 1, 3, parameter);
            case JITTER_MIN_TICKS -> {
                int parsed = integerValue(value, 1, 60, parameter);
                if (parsed > resolveJitterMaxTicks()) {
                    throw new IllegalArgumentException("jitterMinTicks 不能大于 jitterMaxTicks");
                }
                jitterMinTicksOverride = parsed;
            }
            case JITTER_MAX_TICKS -> {
                int parsed = integerValue(value, 1, 60, parameter);
                if (parsed < resolveJitterMinTicks()) {
                    throw new IllegalArgumentException("jitterMaxTicks 不能小于 jitterMinTicks");
                }
                jitterMaxTicksOverride = parsed;
            }
            case TURBULENCE_DECAY -> turbulenceDecayOverride = finiteFloat(value, 0.0f, 1.0f, parameter);
            case JITTER_HORIZONTAL_SCALE ->
                    jitterHorizontalScaleOverride = finiteFloat(value, 0.0f, 8.0f, parameter);
            case JITTER_VERTICAL_SCALE ->
                    jitterVerticalScaleOverride = finiteFloat(value, 0.0f, 8.0f, parameter);
            case JITTER_SPREAD_SCALE ->
                    jitterSpreadScaleOverride = finiteFloat(value, 0.0f, 8.0f, parameter);
            case JITTER_INTERPOLATION ->
                    jitterInterpolationOverride = finiteFloat(value, 0.0f, 1.0f, parameter);
            case ROTATION_RANDOM_DEGREES ->
                    rotationRandomDegreesOverride = finiteFloat(value, 0.0f, 180.0f, parameter);
            case JITTER_DURATION_RATIO ->
                    jitterDurationRatioOverride = finiteFloat(value, 0.0f, 1.0f, parameter);
            case ENABLED, GROUND_WASH -> throw new IllegalStateException("布尔参数未走布尔解析分支");
        }
    }

    /** 清除一个参数的运行时覆盖，使其恢复跟随 JSON 或代码默认值。 */
    public static void reset(Parameter parameter) {
        switch (parameter) {
            case PARTICLE_SCALE -> particleScaleOverride = null;
            case STEP -> stepOverride = null;
            case SPAWN_INTERVAL -> spawnIntervalOverride = null;
            case DENSITY_SCALE -> densityScaleOverride = null;
            case OFFSET -> offsetOverride = null;
            case LAUNCH_BOOST -> launchBoostOverride = null;
            case ENABLED -> enabledOverride = null;
            case GROUND_WASH -> groundWashOverride = null;
            case HOLD_MIN_TICKS -> holdMinTicksOverride = null;
            case HOLD_BUFFER_TICKS -> holdBufferTicksOverride = null;
            case FADE_TICKS -> fadeTicksOverride = null;
            case FADE_RANDOM_TICKS -> fadeRandomTicksOverride = null;
            case FLAME_TICKS -> flameTicksOverride = null;
            case FLAME_RANDOM_TICKS -> flameRandomTicksOverride = null;
            case SMOKE_GREY_MIN -> smokeGreyMinOverride = null;
            case SMOKE_GREY_SPREAD -> smokeGreySpreadOverride = null;
            case TRAIL_LAYERS -> trailLayersOverride = null;
            case JITTER_MIN_TICKS -> jitterMinTicksOverride = null;
            case JITTER_MAX_TICKS -> jitterMaxTicksOverride = null;
            case TURBULENCE_DECAY -> turbulenceDecayOverride = null;
            case JITTER_HORIZONTAL_SCALE -> jitterHorizontalScaleOverride = null;
            case JITTER_VERTICAL_SCALE -> jitterVerticalScaleOverride = null;
            case JITTER_SPREAD_SCALE -> jitterSpreadScaleOverride = null;
            case JITTER_INTERPOLATION -> jitterInterpolationOverride = null;
            case ROTATION_RANDOM_DEGREES -> rotationRandomDegreesOverride = null;
            case JITTER_DURATION_RATIO -> jitterDurationRatioOverride = null;
        }
    }

    /** 清除全部运行时覆盖。 */
    public static void resetAll() {
        for (Parameter parameter : Parameter.values()) {
            reset(parameter);
        }
    }

    /** 返回一条适合聊天栏显示的参数状态。 */
    public static String describe(Parameter parameter) {
        String value = switch (parameter) {
            case PARTICLE_SCALE -> overrideOrUnset(particleScaleOverride);
            case STEP -> overrideOrUnset(stepOverride);
            case SPAWN_INTERVAL -> overrideOrUnset(spawnIntervalOverride);
            case DENSITY_SCALE -> overrideOrUnset(densityScaleOverride);
            case OFFSET -> overrideOrUnset(offsetOverride);
            case LAUNCH_BOOST -> overrideOrUnset(launchBoostOverride);
            case ENABLED -> overrideOrUnset(enabledOverride);
            case GROUND_WASH -> overrideOrUnset(groundWashOverride);
            case HOLD_MIN_TICKS -> overrideOrDefault(holdMinTicksOverride, DEFAULT_HOLD_MIN_TICKS);
            case HOLD_BUFFER_TICKS -> overrideOrDefault(holdBufferTicksOverride, DEFAULT_HOLD_BUFFER_TICKS);
            case FADE_TICKS -> overrideOrDefault(fadeTicksOverride, DEFAULT_FADE_TICKS);
            case FADE_RANDOM_TICKS -> overrideOrDefault(fadeRandomTicksOverride, DEFAULT_FADE_RANDOM_TICKS);
            case FLAME_TICKS -> overrideOrDefault(flameTicksOverride, DEFAULT_FLAME_TICKS);
            case FLAME_RANDOM_TICKS -> overrideOrDefault(flameRandomTicksOverride, DEFAULT_FLAME_RANDOM_TICKS);
            case SMOKE_GREY_MIN -> overrideOrDefault(smokeGreyMinOverride, DEFAULT_SMOKE_GREY_MIN);
            case SMOKE_GREY_SPREAD -> overrideOrDefault(smokeGreySpreadOverride, DEFAULT_SMOKE_GREY_SPREAD);
            case TRAIL_LAYERS -> overrideOrDefault(trailLayersOverride, DEFAULT_TRAIL_LAYERS);
            case JITTER_MIN_TICKS -> overrideOrDefault(jitterMinTicksOverride, DEFAULT_JITTER_MIN_TICKS);
            case JITTER_MAX_TICKS -> overrideOrDefault(jitterMaxTicksOverride, DEFAULT_JITTER_MAX_TICKS);
            case TURBULENCE_DECAY -> overrideOrDefault(turbulenceDecayOverride, DEFAULT_TURBULENCE_DECAY);
            case JITTER_HORIZONTAL_SCALE ->
                    overrideOrDefault(jitterHorizontalScaleOverride, DEFAULT_JITTER_HORIZONTAL_SCALE);
            case JITTER_VERTICAL_SCALE ->
                    overrideOrDefault(jitterVerticalScaleOverride, DEFAULT_JITTER_VERTICAL_SCALE);
            case JITTER_SPREAD_SCALE ->
                    overrideOrDefault(jitterSpreadScaleOverride, DEFAULT_JITTER_SPREAD_SCALE);
            case JITTER_INTERPOLATION ->
                    overrideOrDefault(jitterInterpolationOverride, DEFAULT_JITTER_INTERPOLATION);
            case ROTATION_RANDOM_DEGREES ->
                    overrideOrDefault(rotationRandomDegreesOverride, DEFAULT_ROTATION_RANDOM_DEGREES);
            case JITTER_DURATION_RATIO ->
                    overrideOrDefault(jitterDurationRatioOverride, DEFAULT_JITTER_DURATION_RATIO);
        };
        return parameter.commandName + "=" + value + "（" + parameter.description + "）";
    }

    /** 解析有限双精度数。 */
    private static double parseFiniteDouble(String rawValue) {
        try {
            double value = Double.parseDouble(rawValue);
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("数值格式无效：" + rawValue);
        }
    }

    /** 解析并约束浮点参数。 */
    private static float finiteFloat(double value, float min, float max, Parameter parameter) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(parameter.commandName + " 必须位于 " + min + "～" + max);
        }
        return (float) value;
    }

    /** 解析并约束整数参数，拒绝小数 Tick。 */
    private static int integerValue(double value, int min, int max, Parameter parameter) {
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException(parameter.commandName + " 必须是整数 Tick");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(parameter.commandName + " 必须位于 " + min + "～" + max);
        }
        return (int) value;
    }

    /** 解析布尔参数，兼容 true/false 与 on/off。 */
    private static boolean parseBoolean(String rawValue) {
        return switch (rawValue.toLowerCase(Locale.ROOT)) {
            case "true", "on", "1" -> true;
            case "false", "off", "0" -> false;
            default -> throw new IllegalArgumentException("布尔值只能是 true/false 或 on/off");
        };
    }

    /** 显示可选覆盖值；空值表示没有覆盖。 */
    private static String overrideOrUnset(Object value) {
        return value == null ? "未覆盖" : format(value);
    }

    /** 显示覆盖值或代码默认值。 */
    private static String overrideOrDefault(Object override, Object defaultValue) {
        return override == null ? format(defaultValue) + "（默认）" : format(override) + "（覆盖）";
    }

    /** 统一格式化聊天栏中的浮点数。 */
    private static String format(Object value) {
        if (value instanceof Float || value instanceof Double) {
            return String.format(Locale.ROOT, "%.3f", ((Number) value).doubleValue());
        }
        return String.valueOf(value);
    }
}
