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
        /** 总开关/光晕/发动机照亮与外晕开关覆盖，未设置时沿用 TOML；不开放真实方块光。 */
        enabled("开关：0关闭、1开启；engine控制发动机环境光与广域光晕，保留喷焰；未覆盖时沿用客户端配置（默认开启）", 0, 1,
                1, Float.NaN, Float.NaN, 1, 1),
        /** 环境适应总开关；只调照亮、外围光晕和天空闪光，保留发光核心与贴身光团。 */
        ambientEnabled("环境明暗适应；默认1开启、0恢复原效果，只影响照亮、烟雾受光、外围光晕和天空，核心、贴身光团与喷焰保持自身亮度", 0, 1,
                1, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        /** 当地原版光照的混合权重；剩余权重来自RVP照明前画面，缺区块时只参考画面。 */
        ambientWorldWeight("游戏光照权重；默认0.7即游戏70%加画面30%，范围0～1；当地光照不可用时只用原画面，不加载区块", 0, 1,
                0.7F, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        /** 最亮环境保留的灯光输入比例；0.1即10%，不是最终屏幕像素亮度。 */
        ambientMinStrength("最亮环境保留比例；默认0.1即保留10%的灯光计算输入，暗处仍保留100%，不缩范围或寿命", 0, 1,
                0.1F, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        /** 明暗平滑过渡的指数；默认1，大于1更晚减弱，小于1更早减弱，端点保持不变。 */
        ambientCurve("环境适应过渡曲线；默认1，范围0.1～4；大于1更晚减弱、小于1更早减弱，暗端0.2和亮端0.8不变", 0.1F, 4,
                1, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        /** 环境照明亮度倍率；爆炸默认5、炮口默认4、发动机默认2，爆炸广域光晕另用独立亮度。 */
        brightness("照亮亮度倍率；爆炸默认5、炮口默认4、发动机默认2，不改变爆炸广域光晕或发动机喷焰，炮口光晕沿用炮口光强", 0, 256,
                1, 5, 4, 2, Float.NaN),
        /** 只缩放照亮范围，和光晕分别控制；0 关闭表面/烟雾受光范围。 */
        radiusScale("照亮半径倍率；爆炸默认20、发动机默认5、炮口默认1.2，0关闭照亮，最大8192格，不改变光晕范围", 0, 1024,
                Float.NaN, 20, 1.2F, 5, Float.NaN),
        /** 三类光源的独立光晕范围；爆炸默认开启，炮口默认关闭。 */
        haloRadiusScale("光晕半径倍率；发动机默认5、爆炸默认35、炮口默认0；0关闭该类光晕，最大8192格，不改变照亮范围或喷焰尺寸", 0, 1024,
                Float.NaN, 35, 0, 5, Float.NaN),
        /** 光晕在淡出过程中向中心收缩的强度；只缩光晕半径，照亮、亮度和寿命都不动。 */
        haloShrink("光晕收缩强度；爆炸默认0.1、炮口默认0，0保持固定半径，1在亮度归零时半径也收到中心；只缩光晕，照亮半径、亮度与寿命均不变，持续发光不参与", 0, 1,
                Float.NaN, 0.1F, 0, Float.NaN, Float.NaN),
        /**
         * 天空闪光的峰值亮度：没有深度的天空像素按视线与爆心的夹角染色，形成“整片夜空被闪亮”的观感。
         * 与 brightness、worldHaloIntensity 三者互相独立，避免为了染天空把地面压到过曝。
         */
        skyPeakBrightness("天空峰值亮度；爆炸默认1、炮口默认0，0关闭；只染没有深度的天空像素，与照亮亮度、光晕亮度独立，不占用八灯预算、不进入光晕提取", 0, 8,
                Float.NaN, 1, 0, Float.NaN, Float.NaN),
        /** 天空闪光自身的峰值保持刻数；与光晕的 haloPeakHoldTicks 完全独立。 */
        skyHoldTicks("天空峰值保持刻数；爆炸默认2刻，之后开始淡出；只作用于天空染色，不改变光晕与照亮", 0, 40,
                Float.NaN, 2, 0, Float.NaN, Float.NaN),
        /** 天空闪光自身的淡出时长；与光晕的 haloFadeOutTicks 完全独立。 */
        skyFadeTicks("天空淡出刻数；爆炸默认38刻，保持结束后按三次平滑降到0；天空寿命=保持+淡出，只作用于天空染色", 0, 640,
                Float.NaN, 38, 0, Float.NaN, Float.NaN),
        /** 天空亮区随时间向爆心收拢的强度；只改变方向分布，不改变地面照亮与光晕。 */
        skyContract("天空收拢幅度；爆炸默认1，0只整体变暗、1在熄灭时只留爆心方向；只改变天空染色的方向分布，不改变照亮、光晕或寿命", 0, 1,
                Float.NaN, 1, 1, Float.NaN, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘红色。 */
        red("环境光红色倍率；最终颜色限制为0～1", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘绿色。 */
        green("环境光绿色倍率；最终颜色限制为0～1", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 在原有色彩与爆炸变色曲线之上乘蓝色。 */
        blue("环境光蓝色倍率；最终颜色限制为0～1", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 表面受光输入的倍率；保留既有距离与遮挡算法。 */
        surface("表面受光倍率；0关闭表面和烟雾受光，不改变光晕", 0, 16, Float.NaN, 1, 1, 1, Float.NaN),
        /** 调节照亮衰减和寿命；光晕曲线0～2同步，曲线3使用独立时长，合并窗口不变。 */
        decayScale("照亮衰减倍率；照亮寿命=基础寿命/倍率，光晕曲线0～2同步、曲线3独立；最短1刻，0按0.05慢速淡出", 0, 64,
                Float.NaN, 1, 1, Float.NaN, Float.NaN),
        /** 仅替换有寿命光晕的亮度曲线；爆炸默认高亮保持后淡出，全局显式设置可覆盖来源默认。 */
        haloDecayCurve("光晕曲线：0原有、1线性、2快降后保持、3高亮保持后淡出；爆炸默认3、发动机默认1；只影响光晕，全局显式设置可统一覆盖来源默认", 0, 3,
                0, 3, 0, 1, Float.NaN),
        /** 曲线3的峰值保持时间按真实游戏刻计算；只改变光晕，不延长环境照亮寿命。 */
        haloPeakHoldTicks("高亮保持刻数；爆炸默认5刻，之后从设定亮度平滑降至0；仅曲线3生效，不随照亮寿命倍率伸缩", 0, 40,
                0, 5, 0, 0, Float.NaN),
        /** 曲线3的独立淡出时间；0表示沿用照亮剩余寿命，至少淡出1刻。 */
        haloFadeOutTicks("高亮保持结束后的光晕淡出刻数；爆炸默认70刻，0自动使用照亮剩余寿命；仅曲线3生效，调长或调短都不改变环境照亮", 0, 640,
                0, 70, 0, 0, Float.NaN),
        /** 快速降亮段占总寿命的比例；只在快降后保持曲线下生效。 */
        haloDropFraction("快降段占总寿命的比例；默认0.001，例如1.6秒光晕在1.6毫秒内降至低亮；仅曲线2生效", 0.001F, 0.5F,
                0.001F, 0.001F, 0.001F, 0.001F, Float.NaN),
        /** 保持段相对初始光晕亮度的比例；不再叠乘原淡出。 */
        haloLowBrightness("保持段相对初始光晕亮度的比例；默认0.1即10%，仅曲线2生效", 0, 1,
                0.1F, 0.1F, 0.1F, 0.1F, Float.NaN),
        /** 低亮保持到的寿命位置，余下时间柔和消失。 */
        haloHoldUntil("低亮保持到的寿命比例；默认0.99，最后1%寿命柔和消失；仅曲线2生效", 0.5F, 0.99F,
                0.99F, 0.99F, 0.99F, 0.99F, Float.NaN),
        /** 沿用当前浮点纹理安全峰值，不允许调参超过上限。 */
        coreIntensity("自发光核心亮度；爆炸默认40、发动机默认60000，HDR是显示前高亮数值，0会关闭对应核心（爆炸可只留外围光团）", 0, RVP_LightParameters.ENGINE_CORE_INTENSITY,
                Float.NaN, 40, Float.NaN, RVP_LightParameters.ENGINE_CORE_INTENSITY, Float.NaN),
        /** 发动机喷焰外围橙焰亮度；爆炸的核心光团另有 coreHaloIntensity，两者互不影响。 */
        haloIntensity("喷焰外围橙焰亮度；默认5，0关闭外围橙焰，不改变广域橙晕；爆炸核心光团请用 coreHaloIntensity", 0, 60000,
                Float.NaN, Float.NaN, Float.NaN, 5, Float.NaN),
        /** 爆炸核心外围光团的独立亮度；与喷焰 haloIntensity 分开，避免两类效果互相牵制。 */
        coreHaloIntensity("核心外围光团亮度；爆炸默认40，0关闭光团但白热核心仍显示；与喷焰 haloIntensity 相互独立", 0, 60000,
                Float.NaN, 40, Float.NaN, Float.NaN, Float.NaN),
        /** 爆炸白热核心的出生半径；实际半径再乘当量倍率并按寿命收缩。 */
        coreRadius("自发光核心半径（参考当量下、出生时的最大格数）；爆炸默认0.8格，0关闭整个核心，实际半径=本值×coreRadiusScale×基础半径倍数×(1-coreShrink×寿命进度)", 0, 64,
                Float.NaN, 0.8F, Float.NaN, Float.NaN, Float.NaN),
        /** 核心半径随爆炸当量的缩放强度；1 表示与基础半径线性相关，0 表示不随当量变化。 */
        coreRadiusScale("核心半径当量倍率；爆炸默认1，实际半径按基础半径/12线性缩放（当量越大核心越大），0固定为参考值、越大越夸张", 0, 8,
                Float.NaN, 1F, Float.NaN, Float.NaN, Float.NaN),
        /** 核心随寿命收缩的幅度；火球从最大半径开始向中心收，0 保持固定半径。 */
        coreShrink("核心缩小幅度；爆炸默认0.3，寿命终点缩到出生半径的(1-幅度)，0保持固定、1缩到中心；同时作用于外围光团，不改变亮度或光照", 0, 1,
                Float.NaN, 0.3F, Float.NaN, Float.NaN, Float.NaN),
        /** 核心外围光团的出生半径；着色器另有“不小于核心半径3.5倍”的下限。 */
        coreHaloRadius("核心外围光团半径（参考当量下、出生时的最大格数）；爆炸默认10格，实际半径=本值×coreHaloScale×基础半径倍数×(1-coreShrink×寿命进度)", 0, 128,
                Float.NaN, 10.0F, Float.NaN, Float.NaN, Float.NaN),
        /** 核心光团半径随爆炸当量的缩放强度；与 coreRadiusScale 分开，可单独控制光团大小。 */
        coreHaloScale("核心光团当量缩放；爆炸默认1，按基础半径/12线性缩放，0固定为参考值；与 coreRadiusScale 独立", 0, 8,
                Float.NaN, 1F, Float.NaN, Float.NaN, Float.NaN),
        /** 爆炸和发动机广域光晕的独立亮度；不随爆炸照亮亮度倍率放大。 */
        worldHaloIntensity("广域光晕独立亮度；爆炸默认0.6、发动机默认0.4，爆炸0关闭其光晕，发动机0只关闭广域橙晕，不改变照亮", 0, 60000,
                Float.NaN, 0.6F, Float.NaN, 0.4F, Float.NaN),
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
        /** 环境光距离预算倍率，喷焰与天空各有独立预算，不受此倍率影响。 */
        distanceScale("环境光观察距离倍率；最终限制为1～32768格，喷焰与天空散射不受此预算限制", 0.0001F, 128,
                1, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        /** 天空散射的专用距离预算；高空空爆爆心很远，不能沿用地面照亮的预算。 */
        skyDistance("天空散射专用距离预算，单位格；默认2048，0表示沿用普通距离预算（maxDistance+可见半径）；只放宽/收紧天空染色，不影响照亮、光晕与核心", 0, (float) RVP_LightRuntimeTuning.MAX_TUNED_DISTANCE,
                2048, Float.NaN, Float.NaN, Float.NaN, Float.NaN),
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

        /** 两种开关共用整数校验与面板控件，环境适应开关不读取原有画质TOML。 */
        public boolean toggle() { return this == enabled || this == ambientEnabled; }

        /** 光晕时间参数可以由 global 统一设置，也可按来源覆盖。 */
        public boolean haloTimeline() {
            return this == haloDecayCurve || this == haloPeakHoldTicks || this == haloFadeOutTicks || this == haloDropFraction
                    || this == haloLowBrightness || this == haloHoldUntil;
        }
    }

    private RVP_LightRuntimeTuning() { }

    /** 读取当前值；没有临时覆盖时使用各分组的代码默认值。 */
    public static float get(Group group, Parameter parameter) {
        // 调用带回退值的读取入口，保持所有倍率的默认语义一致。
        return getOrDefault(group, parameter, defaultValue(group, parameter));
    }

    /** 分组光晕未覆盖时继承全局显式值，否则使用来源自己的默认值，例如爆炸3刻高亮保持。 */
    public static float defaultValue(Group group, Parameter parameter) {
        // 调用全局读取入口，单项重置后自然恢复全局曲线，不制造额外覆盖。
        return group != Group.global && parameter.supports(group) && parameter.haloTimeline()
                ? getOrDefault(Group.global, parameter, parameter.defaultValue(group)) : parameter.defaultValue(group);
    }

    /** 有限寿命光晕的统一采样入口；持续运行的光源由调用方保持亮起，不套虚假的倒计时。 */
    public static float haloFade(Group group, double ageTicks, float durationTicks, float originalFade) {
        Group source = group == null ? Group.global : group;
        // 调用统一参数读取和纯曲线函数；光晕单独求值，避免环境照亮的衰减再次压暗保持段。
        RVP_HaloDecayCurve curve = RVP_HaloDecayCurve.values()[(int) get(source, Parameter.haloDecayCurve)];
        if (curve == RVP_HaloDecayCurve.PEAK_HOLD_FADE) {
            // 调用实际游戏刻入口，爆炸的前3刻不随decayScale变成1.5刻或6刻。
            return RVP_HaloDecayCurve.peakHoldFade(ageTicks, get(source, Parameter.haloPeakHoldTicks),
                    haloFadeOutTicks(source, durationTicks));
        }
        float progress = (float) (ageTicks / durationTicks);
        return curve.fade(progress, originalFade,
                get(source, Parameter.haloDropFraction), get(source, Parameter.haloLowBrightness),
                get(source, Parameter.haloHoldUntil));
    }

    /** 光晕曲线3可以独立延长或缩短；其它曲线仍沿用原光源寿命。 */
    public static float haloDurationTicks(Group group, float lightDurationTicks) {
        Group source = group == null ? Group.global : group;
        // 调用同一曲线和时长设置，迟包判断、渲染采样和逻辑清理使用一致的结束边界。
        return get(source, Parameter.haloDecayCurve) == RVP_HaloDecayCurve.PEAK_HOLD_FADE.ordinal()
                ? get(source, Parameter.haloPeakHoldTicks) + haloFadeOutTicks(source, lightDurationTicks)
                : lightDurationTicks;
    }

    /** 淡出时间0表示自动跟随照亮剩余寿命，仍预留至少1游戏刻柔和归零。 */
    private static float haloFadeOutTicks(Group group, float lightDurationTicks) {
        // 调用独立时长参数；显式设置时不再读取或反向修改照亮的decayScale。
        float configured = get(group, Parameter.haloFadeOutTicks);
        return configured > 0 ? configured : Math.max(1, lightDurationTicks - get(group, Parameter.haloPeakHoldTicks));
    }

    /** 开关未被命令覆盖时继续尊重用户已有 TOML，避免默认调参覆盖画质选择。 */
    public static float getOrDefault(Group group, Parameter parameter, float fallback) {
        return overrides.getOrDefault(group, Map.of()).getOrDefault(parameter, fallback);
    }

    /** 校验后替换覆盖表；额外拒绝 NaN/无穷和小数开关，任何失败都保留原值。 */
    public static synchronized void set(Group group, Parameter parameter, float value) {
        if (!parameter.supports(group) || !Float.isFinite(value) || value < parameter.min || value > parameter.max
                || parameter.toggle() && value != 0 && value != 1
                || parameter == Parameter.haloDecayCurve && value != Math.rint(value)) {
            throw new IllegalArgumentException("参数不适用或数值越界：" + group + "." + parameter
                    + "，允许 " + parameter.min + "～" + parameter.max
                    + (parameter.toggle() ? "，开关只接受0或1" : "")
                    + (parameter == Parameter.haloDecayCurve ? "，曲线只接受整数0、1、2、3" : ""));
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
        return Math.min(MAX_TUNED_RADIUS, Math.max(0, baseRadius * get(group, Parameter.radiusScale)));
    }

    /** 光晕始终从原始基础半径计算；改照亮半径不会改变此结果。 */
    public static float haloRadius(Group group, float baseRadius) {
        // 调用独立光晕倍率，0 也会关闭发动机的模糊来源和广域橙晕，核心继续显示。
        return Math.min(MAX_TUNED_RADIUS, Math.max(0, baseRadius * get(group, Parameter.haloRadiusScale)));
    }

    /** 光晕随淡出向中心收缩的半径倍率；持续光和不支持该参数的分组保持 1 倍。 */
    public static float haloShrinkFactor(Group group, float haloFade) {
        // 调用统一参数读取；NaN 表示该分组不参与收缩，例如持续发光的发动机和干扰机。
        float shrink = group == null ? Float.NaN : get(group, Parameter.haloShrink);
        // 调用有限性检查，非法调参或非有限淡出不能把 NaN 半径交给渲染。
        if (!Float.isFinite(shrink) || shrink <= 0 || !Float.isFinite(haloFade)) { return 1; }
        float remaining = Math.max(0, Math.min(1, haloFade));
        // 调用收缩公式：淡出比例为1时不缩，接近0时半径收到中心；强度0时恒为1。
        return 1 - shrink * (1 - remaining);
    }

    /** 采样时的光晕半径：静态范围乘收缩倍率；只缩光晕，照亮半径仍由 radius(...) 独立决定。 */
    public static float sampledHaloRadius(Group group, float baseRadius, float haloFade) {
        // 调用静态范围与收缩倍率；两种倍率各自从原始基数计算，不逐帧累乘。
        return haloRadius(group, baseRadius) * haloShrinkFactor(group, haloFade);
    }

    /** 天空闪光自身的亮度比例：先按 skyHoldTicks 保持峰值，再用 skyFadeTicks 三次平滑归零。 */
    public static float skyFade(Group group, double ageTicks) {
        // 调用统一参数读取；NaN 表示该分组不写天空染色，例如持续发光的发动机。
        float peak = group == null ? Float.NaN : get(group, Parameter.skyPeakBrightness);
        if (!Float.isFinite(peak) || peak <= 0 || !Double.isFinite(ageTicks)) { return 0; }
        float hold = Math.max(0, Math.min(40, get(group, Parameter.skyHoldTicks)));
        float fade = Math.max(0, Math.min(640, get(group, Parameter.skyFadeTicks)));
        // 调用与光晕曲线3同形的“保持后平滑淡出”，但使用天空自己的刻数，不受 halo 时间线影响。
        return RVP_HaloDecayCurve.peakHoldFade(ageTicks, hold, Math.max(1, fade));
    }

    /** 天空闪光的整段时长，单位刻；用于让光源活到天空熄灭之后，而不是被照亮寿命提前删除。 */
    public static float skyDurationTicks(Group group) {
        if (group == null) { return 0; }
        float peak = get(group, Parameter.skyPeakBrightness);
        float hold = get(group, Parameter.skyHoldTicks);
        float fade = get(group, Parameter.skyFadeTicks);
        // 调用参数读取；未启用或参数无效时返回 0，不延长光源寿命。
        if (!Float.isFinite(peak) || peak <= 0 || !Float.isFinite(hold) || !Float.isFinite(fade)) { return 0; }
        // 与 skyFade 使用同一修正：淡出至少 1 刻，保证光源活到天空真正归零。
        return Math.max(0, Math.min(40, hold)) + Math.max(1, Math.min(640, fade));
    }

    /** 天空散射强度：峰值亮度乘天空自身的淡出比例；持续光和不支持该参数的分组为 0。 */
    public static float skyIntensity(Group group, double ageTicks) {
        float peak = group == null ? Float.NaN : get(group, Parameter.skyPeakBrightness);
        // 调用有限性检查；非法调参或非有限年龄按关闭处理，不能把 NaN 送进着色器。
        if (!Float.isFinite(peak) || peak <= 0) { return 0; }
        // 调用天空自己的淡出比例，与光晕曲线、保持刻数都无关。
        return peak * skyFade(group, ageTicks);
    }

    /** 天空亮区宽度：1 为整片覆盖，0 为只在爆心方向；随天空淡出按收拢幅度变窄。 */
    public static float skySpread(Group group, double ageTicks) {
        // 调用收拢幅度；不支持该参数时保持最宽，不制造额外的方向变化。
        float contract = group == null ? Float.NaN : get(group, Parameter.skyContract);
        if (!Float.isFinite(contract) || !Double.isFinite(ageTicks)) { return 1; }
        float remaining = skyFade(group, ageTicks);
        // 调用收拢公式：满亮时最宽，熄灭时只剩爆心方向；幅度为0时恒为整片。
        return 1 - Math.max(0, Math.min(1, contract)) * (1 - remaining);
    }

    /**
     * 爆炸白热核心的自发光参数：圆形核心 + 外围光团，与喷焰共用 {@link RVP_LightParameters.EngineEmission} 结构。
     *
     * <p>尺寸 = 出生半径 × 当量倍率 × (爆炸基础半径 / 12) × 寿命收缩：
     * {@code coreRadius/coreHaloRadius} 是“参考当量（爆炸半径 4 格）”下**出生时的最大格数**，
     * {@code coreRadiusScale/coreHaloScale} 控制随当量线性放大的强度（0 = 不随当量变化），
     * {@code coreShrink} 让火球从最大半径随寿命线性收缩。亮度跟随光晕淡出比例；
     * 核心亮度为 0 时仍可只保留外围光团。</p>
     *
     * @param group 爆炸分组；其它分组返回 null
     * @param baseRadius 爆炸基础半径（未调参的照明半径基数），单位格
     * @param progress 已过寿命比例 0～1，用于收缩
     * @param haloFade 光晕淡出比例 0～1，用于亮度
     */
    public static RVP_LightParameters.EngineEmission coreEmission(Group group, float baseRadius,
            float progress, float haloFade) {
        if (group == null || !Float.isFinite(progress) || !Float.isFinite(haloFade) || haloFade <= 0) { return null; }
        float coreRadius = get(group, Parameter.coreRadius);
        float coreScale = get(group, Parameter.coreRadiusScale);
        float haloRadius = get(group, Parameter.coreHaloRadius);
        float haloScale = get(group, Parameter.coreHaloScale);
        float shrink = get(group, Parameter.coreShrink);
        float coreIntensity = get(group, Parameter.coreIntensity);
        float haloIntensity = get(group, Parameter.coreHaloIntensity);
        // 调用有限性检查：尺寸或倍率无效、两路亮度都为 0 时不产生自发光，避免把 NaN 交给渲染。
        if (!Float.isFinite(coreRadius) || coreRadius <= 0 || !Float.isFinite(coreScale) || coreScale <= 0
                || !Float.isFinite(coreIntensity) || !Float.isFinite(haloIntensity)
                || (coreIntensity <= 0 && haloIntensity <= 0)) { return null; }
        // 调用当量参考：以爆炸半径 4 格（基础半径 12 格）为 1 倍，当量越大核心与光团越大。
        float reference = Float.isFinite(baseRadius) && baseRadius > 0
                ? baseRadius / RVP_LightParameters.CORE_REFERENCE_BASE : 1;
        // 调用收缩公式：出生时是最大半径，寿命终点缩到 (1-幅度)；保留 5% 下限，避免半径归零后整条自发光消失。
        float contract = (Float.isFinite(shrink) ? Math.max(0, Math.min(1, shrink)) : 0)
                * Math.max(0, Math.min(1, progress));
        float scaleOverLife = Math.max(0.05F, 1 - contract);
        // 调用尺寸上限：调参允许很大，但半径过百格的发光球没有实际意义，限制在 512 格内。
        float core = Math.min(512, coreRadius * Math.max(0, Math.min(8, coreScale)) * reference * scaleOverLife);
        float halo = Math.min(512, Math.max(core, (Float.isFinite(haloRadius) ? haloRadius : 0)
                * (Float.isFinite(haloScale) ? Math.max(0, Math.min(8, haloScale)) : 0) * reference * scaleOverLife));
        return new RVP_LightParameters.EngineEmission(core, halo,
                coreIntensity * Math.max(0, Math.min(1, haloFade)),
                haloIntensity * Math.max(0, Math.min(1, haloFade)));
    }

    /** 每帧从原始预设生成调参输入；年龄、颜色过渡、遮挡与预算仍交给现有管理器。 */
    public static RVP_LightParameters apply(Group group, RVP_LightParameters base) {
        // 三类光源都应用代码默认倍率，不能因没有临时覆盖而跳过炮口与发动机的新默认值。
        // 调用统一参数读取和半径入口；保留原始寿命、合并窗口和光晕标记，不反写已有预设。
        return new RVP_LightParameters(base.enabled(),
                Math.min(1, base.red() * get(group, Parameter.red)),
                Math.min(1, base.green() * get(group, Parameter.green)),
                Math.min(1, base.blue() * get(group, Parameter.blue)), radius(group, base.radius()),
                base.intensity() * get(group, Parameter.brightness), base.durationTicks(), base.mergeWindowTicks(),
                base.surfaceWeight() * get(group, Parameter.surface),
                base.decay() * (group == Group.engine ? 1 : get(group, Parameter.decayScale)),
                base.bloom(), base.volumetricStrength(), base.blockLightLevel());
    }
}
