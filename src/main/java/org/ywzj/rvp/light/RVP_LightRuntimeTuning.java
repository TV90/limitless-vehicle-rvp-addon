package org.ywzj.rvp.light;

import java.util.EnumMap;
import java.util.Map;

/** 光照与光晕的本机会话调参；只改变既有算法的输入，不写配置、资源或载具包。 */
public final class RVP_LightRuntimeTuning {
    /** 游戏内两种范围各自的上限，单位格；仅约束调参，不改变原有代码基础半径。 */
    public static final float MAX_TUNED_RADIUS = 8192F;
    /** 调参后环境光的最远观察距离，单位格；光源影响范围仍会加入距离预算。 */
    public static final double MAX_TUNED_DISTANCE = 32768D;
    /** 不可变覆盖表；写入时整表替换，渲染读取不会看到修改了一半的数据。 */
    private static volatile Map<Group, Map<Parameter, Float>> overrides = Map.of();

    /** 游戏内指令使用的参数分组。 */
    public enum Group {
        /** 环境照明总设置，默认继续读取客户端画质配置。 */ global,
        /** 爆炸短光。 */ explosion,
        /** 炮口短光。 */ muzzle,
        /** 发动机环境照明、程序喷焰和广域橙色光晕。 */ engine,
        /** 多尺度亮部提取、近层与远层光晕。 */ bloom
    }

    /** 参数元数据；不适用的分组以 NaN 标记，命令不会注册对应节点。 */
    public enum Parameter {
        /** 总开关覆盖，未设置时沿用 TOML；不开放真实方块光。 */
        enabled("开关：0关闭、1开启；未覆盖时沿用客户端配置", 0, 1, 1, Float.NaN, Float.NaN, Float.NaN, 1),
        /** 环境照明亮度倍率；爆炸默认20、炮口默认4、发动机默认2，爆炸广域光晕另用独立亮度。 */
        brightness("照亮亮度倍率；爆炸默认20、炮口默认4、发动机默认2，不改变爆炸广域光晕或发动机喷焰，炮口光晕沿用炮口光强", 0, 256,
                1, 20, 4, 2, Float.NaN),
        /** 只缩放照亮范围，和光晕分别控制；0 关闭表面/烟雾受光范围。 */
        radiusScale("照亮半径倍率；爆炸和发动机默认5、炮口默认1.2，0关闭照亮，最大8192格，不改变光晕范围", 0, 1024,
                Float.NaN, 5, 1.2F, 5, Float.NaN),
        /** 三类光源的独立光晕范围；爆炸与炮口暂默认关闭，不影响照亮范围。 */
        haloRadiusScale("光晕半径倍率；发动机默认5，爆炸和炮口暂默认0，0关闭该类光晕，最大8192格，不改变照亮范围或喷焰尺寸", 0, 1024,
                Float.NaN, 0, 0, 5, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘红色。 */
        red("环境光红色倍率；最终颜色限制为0～1", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘绿色。 */
        green("环境光绿色倍率；最终颜色限制为0～1", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘蓝色。 */
        blue("环境光蓝色倍率；最终颜色限制为0～1", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 表面受光输入的倍率；保留既有距离与遮挡算法。 */
        surface("表面受光倍率；0关闭表面和烟雾受光，不改变光晕", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 调节整盏短光的衰减和寿命；照亮/光晕同步，合并窗口不变。 */
        decayScale("衰减倍率；整盏光源寿命=基础寿命/倍率，照亮和光晕同步，最短1刻，0按0.05慢速淡出", 0, 64,
                Float.NaN, 1, 1, Float.NaN, Float.NaN),
        /** 沿用当前浮点纹理安全峰值，不允许调参超过上限。 */
        coreIntensity("喷焰白热核心亮度；HDR是显示前高亮数值，0会关闭整条喷焰及其光晕", 0, RVP_LightParameters.ENGINE_CORE_INTENSITY,
                Float.NaN, Float.NaN, Float.NaN, RVP_LightParameters.ENGINE_CORE_INTENSITY, Float.NaN),
        /** 喷焰外围橙焰亮度，默认5，和喷口周围的广域橙晕分别控制。 */
        haloIntensity("喷焰外围橙焰亮度；默认5，0关闭外围橙焰，不改变广域橙晕", 0, 60000,
                Float.NaN, Float.NaN, Float.NaN, 5, Float.NaN),
        /** 爆炸和发动机广域光晕的独立亮度；不随爆炸照亮亮度倍率放大。 */
        worldHaloIntensity("广域光晕独立亮度；爆炸默认16、发动机默认0.4，爆炸0关闭其光晕，发动机0只关闭广域橙晕，不改变照亮", 0, 60000,
                Float.NaN, 16, Float.NaN, 0.4F, Float.NaN),
        /** 光晕提取前的既有软压缩峰值，不改变核心显示亮度。 */
        inputPeak("喷焰进入模糊光晕的亮度上限；不改变白热核心与广域橙晕", 0.00001F, 60000,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, RVP_RocketPlumeParameters.BLOOM_INPUT_PEAK),
        /** 各层高斯模糊采样间距倍率，缩小和回升所需的连续过滤始终保留。 */
        blurScale("各层柔化范围倍率；0关闭额外高斯模糊，仍保留缩放过滤", 0, 32,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1),
        /** 二分之一和四分之一尺寸两层的能量倍率；不改变远层和核心。 */
        nearStrength("近层光晕强度倍率；0关闭贴近亮焰的两层扩散，不改变远层", 0, 256,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1),
        /** 八分之一和十六分之一尺寸两层的能量倍率；不改变近层和核心。 */
        farStrength("远层光晕强度倍率；0关闭向周围消散的两层辉光，不改变近层", 0, 256,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1),
        /** 环境光距离预算倍率，喷焰的独立远距可见性不受影响。 */
        distanceScale("环境光观察距离倍率；最终限制为1～32768格，喷焰不受此预算限制", 0.0001F, 128,
                1, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        /** 烟雾额外受光亮度，可独立于墙面/地面调节。 */
        smokeBrightness("烟雾及炮口特殊贴图的受光倍率；0关闭这层受光", 0, 256,
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

    /** 读取当前值；没有临时覆盖时使用各分组的代码默认值。 */
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

    /** 照亮半径只乘一次，三类光源都允许缩到零或扩大到8192格，光晕另行采样。 */
    public static float radius(Group group, float baseRadius) {
        // 调用独立照亮倍率，不读取光晕参数，防止两种范围重新耦合。
        return Math.min(MAX_TUNED_RADIUS, Math.max(0, baseRadius * scale(group, Parameter.radiusScale)));
    }

    /** 光晕始终从原始基础半径计算；改照亮半径不会改变此结果。 */
    public static float haloRadius(Group group, float baseRadius) {
        // 调用独立光晕倍率，0 也会关闭发动机的模糊来源和广域橙晕，核心继续显示。
        return Math.min(MAX_TUNED_RADIUS, Math.max(0, baseRadius * scale(group, Parameter.haloRadiusScale)));
    }

    /** 分组不开放该倍率时代码默认值是 NaN；采样按 1 处理，不能让 NaN 污染半径、颜色和表面权重。 */
    private static float scale(Group group, Parameter parameter) {
        float value = get(group, parameter);
        return Float.isFinite(value) ? value : 1F;
    }

    /** 每帧从原始预设生成调参输入；年龄、颜色过渡、遮挡与预算仍交给现有管理器。 */
    public static RVP_LightParameters apply(Group group, RVP_LightParameters base) {
        // 三类光源都应用代码默认倍率，不能因没有临时覆盖而跳过炮口与发动机的新默认值。
        // 调用统一参数读取和半径入口；保留原始寿命、合并窗口和光晕标记，不反写已有预设。
        return new RVP_LightParameters(base.enabled(),
                Math.min(1, base.red() * scale(group, Parameter.red)),
                Math.min(1, base.green() * scale(group, Parameter.green)),
                Math.min(1, base.blue() * scale(group, Parameter.blue)), radius(group, base.radius()),
                base.intensity() * get(group, Parameter.brightness), base.durationTicks(), base.mergeWindowTicks(),
                base.surfaceWeight() * scale(group, Parameter.surface),
                base.decay() * (group == Group.engine ? 1 : scale(group, Parameter.decayScale)),
                base.bloom(), base.volumetricStrength(), base.blockLightLevel());
    }
}
