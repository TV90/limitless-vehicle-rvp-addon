package org.ywzj.rvp.light;

import java.util.EnumMap;
import java.util.Map;

/** 光照与光晕的本机会话调参；只改变既有算法的输入，不写配置、资源或载具包。 */
public final class RVP_LightRuntimeTuning {
    /** 不可变覆盖表；写入时整表替换，渲染读取不会看到修改了一半的数据。 */
    private static volatile Map<Group, Map<Parameter, Float>> overrides = Map.of();

    /** 游戏内指令使用的参数分组。 */
    public enum Group {
        /** 环境照明总设置，默认继续读取客户端画质配置。 */ global,
        /** 爆炸短光。 */ explosion,
        /** 炮口短光。 */ muzzle,
        /** 发动机环境照明、程序喷焰和广域橙色光晕。 */ engine,
        /** 现有亮部提取和模糊。 */ bloom
    }

    /** 参数元数据；不适用的分组以 NaN 标记，命令不会注册对应节点。 */
    public enum Parameter {
        /** 总开关覆盖，未设置时沿用 TOML；不开放真实方块光。 */
        enabled("开关：0关闭、1开启；未覆盖时沿用客户端配置", 0, 1, 1, Float.NaN, Float.NaN, Float.NaN, 1),
        /** 环境照明亮度倍率；发动机自发光使用独立的 coreIntensity/haloIntensity。 */
        brightness("环境照明亮度倍率；0关闭受光，不改变发动机喷焰", 0, 8, 1, 1, 1, 1, Float.NaN),
        /** 既有半径公式的倍率，发动机环境光与广域光晕始终同步。 */
        radiusScale("半径倍率；发动机环境光/广域光晕共同限制为20～500格", 0.05F, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘红色。 */
        red("环境光红色倍率；最终颜色限制为0～1", 0, 4, Float.NaN, 1, 1, 1, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘绿色。 */
        green("环境光绿色倍率；最终颜色限制为0～1", 0, 4, Float.NaN, 1, 1, 1, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘蓝色。 */
        blue("环境光蓝色倍率；最终颜色限制为0～1", 0, 4, Float.NaN, 1, 1, 1, Float.NaN),
        /** 表面受光输入的倍率；保留既有距离与遮挡算法。 */
        surface("表面受光倍率；最终权重限制为0～1", 0, 4, Float.NaN, 1, 1, 1, Float.NaN),
        /** 仅调节既有短光衰减指数，不改寿命与合并窗口。 */
        decayScale("短光衰减指数倍率；越大越快变暗，寿命不变", 0.1F, 8, Float.NaN, 1, 1, Float.NaN, Float.NaN),
        /** 沿用当前浮点纹理安全峰值，不允许调参超过上限。 */
        coreIntensity("喷焰白热核心亮度；HDR是显示前高亮数值，0会关闭整条喷焰及其光晕", 0, RVP_LightParameters.ENGINE_CORE_INTENSITY,
                Float.NaN, Float.NaN, Float.NaN, RVP_LightParameters.ENGINE_CORE_INTENSITY, Float.NaN),
        /** 同时供现有橙焰与广域橙色光晕使用的独立亮度。 */
        haloIntensity("发动机橙焰及广域橙色光晕亮度；0关闭橙色分量", 0, 256,
                Float.NaN, Float.NaN, Float.NaN, RVP_LightParameters.ENGINE_HALO_INTENSITY, Float.NaN),
        /** 光晕提取前的既有软压缩峰值，不改变核心显示亮度。 */
        inputPeak("喷焰进入模糊光晕的亮度上限；不改变白热核心与广域橙晕", 0.001F, 64,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, RVP_RocketPlumeParameters.BLOOM_INPUT_PEAK),
        /** 原高斯模糊采样间距倍率，不增加采样次数。 */
        blurScale("屏幕模糊范围倍率；1保留当前范围，0不向周围扩散", 0, 8,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1),
        /** 环境光距离预算倍率，喷焰的独立远距可见性不受影响。 */
        distanceScale("环境光观察距离倍率；最终限制为16～1024格，喷焰不受此预算限制", 0.0625F, 4,
                1, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        /** 烟雾额外受光亮度，可独立于墙面/地面调节。 */
        smokeBrightness("烟雾及炮口特殊贴图的受光倍率；0关闭这层受光", 0, 8,
                1, Float.NaN, Float.NaN, Float.NaN, Float.NaN);

        /** 中文含义、单位及生效边界。 */
        public final String description;
        /** 指令接受的最小值。 */
        public final float min;
        /** 指令接受的最大值。 */
        public final float max;
        /** 与 Group 顺序一致的默认值，不向外暴露数组。 */
        private final float[] defaults;

        Parameter(String description, float min, float max, float... defaults) {
            this.description = description;
            this.min = min;
            this.max = max;
            this.defaults = defaults;
        }

        /** 返回代码默认值，NaN 表示分组不使用该参数。 */
        public float defaultValue(Group group) { return defaults[group.ordinal()]; }

        /** 指令与写入校验共用的适用性检查。 */
        public boolean supports(Group group) { return Float.isFinite(defaultValue(group)); }
    }

    private RVP_LightRuntimeTuning() { }

    /** 读取当前值；没有覆盖时与原代码数值完全一致。 */
    public static float get(Group group, Parameter parameter) {
        // 调用带回退值的读取入口，保持所有倍率的默认语义一致。
        return getOrDefault(group, parameter, parameter.defaultValue(group));
    }

    /** 开关未被命令覆盖时继续尊重用户已有 TOML，避免默认调参覆盖画质选择。 */
    public static float getOrDefault(Group group, Parameter parameter, float fallback) {
        return overrides.getOrDefault(group, Map.of()).getOrDefault(parameter, fallback);
    }

    /** 校验后替换覆盖表；额外拒绝 NaN/无穷和小数开关，任何失败都保留原值。 */
    public static synchronized void set(Group group, Parameter parameter, float value) {
        if (!parameter.supports(group) || !Float.isFinite(value) || value < parameter.min || value > parameter.max
                || parameter == Parameter.enabled && value != 0 && value != 1) {
            throw new IllegalArgumentException("参数不适用或数值越界：" + group + "." + parameter
                    + "，允许 " + parameter.min + "～" + parameter.max
                    + (parameter == Parameter.enabled ? "，开关只接受0或1" : ""));
        }
        Map<Group, Map<Parameter, Float>> next = new EnumMap<>(Group.class);
        next.putAll(overrides);
        Map<Parameter, Float> values = new EnumMap<>(Parameter.class);
        values.putAll(next.getOrDefault(group, Map.of()));
        values.put(parameter, value);
        next.put(group, Map.copyOf(values));
        overrides = Map.copyOf(next);
    }

    /** 清除全部临时值；客户端画质配置和原有代码预设立即重新接管。 */
    public static synchronized void reset() { overrides = Map.of(); }

    /** 只清除一组或一个参数；parameter 为 null 时清除整组。 */
    public static synchronized void reset(Group group, Parameter parameter) {
        Map<Group, Map<Parameter, Float>> next = new EnumMap<>(Group.class);
        next.putAll(overrides);
        if (parameter == null) {
            next.remove(group);
        } else {
            Map<Parameter, Float> values = new EnumMap<>(Parameter.class);
            values.putAll(next.getOrDefault(group, Map.of()));
            values.remove(parameter);
            if (values.isEmpty()) { next.remove(group); }
            else { next.put(group, Map.copyOf(values)); }
        }
        overrides = Map.copyOf(next);
    }

    /** 供状态查询与复制命令读取一致且不可变的覆盖清单。 */
    public static Map<Group, Map<Parameter, Float>> overrides() { return overrides; }

    /** 半径只在采样时乘一次，避免持续光逐帧累计放大；发动机保持原20～500格边界。 */
    public static float radius(Group group, float baseRadius) {
        // 调用同一倍率入口，发动机环境光与程序光晕不能分别维护一套调参结果。
        float result = baseRadius * get(group, Parameter.radiusScale);
        return group == Group.engine ? Math.max(RVP_LightParameters.ENGINE_MIN_RADIUS,
                Math.min(RVP_LightParameters.ENGINE_MAX_RADIUS, result)) : result;
    }

    /** 每帧从原始预设生成调参输入；年龄、颜色过渡、遮挡与预算仍交给现有管理器。 */
    public static RVP_LightParameters apply(Group group, RVP_LightParameters base) {
        // 没有该组覆盖时直接返回原预设，保持既有数值与默认路径的分配成本。
        if (!overrides.containsKey(group)) { return base; }
        // 调用统一参数读取和半径入口；保留原始寿命、合并窗口和光晕标记，不反写已有预设。
        return new RVP_LightParameters(base.enabled(),
                Math.min(1, base.red() * get(group, Parameter.red)),
                Math.min(1, base.green() * get(group, Parameter.green)),
                Math.min(1, base.blue() * get(group, Parameter.blue)), radius(group, base.radius()),
                base.intensity() * get(group, Parameter.brightness), base.durationTicks(), base.mergeWindowTicks(),
                Math.min(1, base.surfaceWeight() * get(group, Parameter.surface)),
                base.decay() * (group == Group.engine ? 1 : get(group, Parameter.decayScale)),
                base.bloom(), base.volumetricStrength(), base.blockLightLevel());
    }
}
