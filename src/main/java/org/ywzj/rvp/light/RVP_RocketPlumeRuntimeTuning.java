package org.ywzj.rvp.light;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/** 程序尾焰的本地美术调参；只保存明确修改的项，不引用客户端类型、不改武器燃烧事实。 */
public final class RVP_RocketPlumeRuntimeTuning {
    /** 与F8读写共用的覆盖；空表表示完整沿用当前程序表现，没有推荐预设。 */
    private static final Map<Parameter, Double> OVERRIDES = new ConcurrentHashMap<>();

    private RVP_RocketPlumeRuntimeTuning() { }

    /** 面向调参流程的固定分组；亮度及照亮范围仍使用原“屏幕空间特效/发动机”分组。 */
    public enum Group {
        /** 从外轮廓开始调长宽和收尖。 */
        SHAPE("shape", "长宽与喷流轮廓"),
        /** 调白热细芯和外焰的软硬。 */
        LAYERS("layers", "核心与外焰层次"),
        /** 三层分别配色，不把环境照明一起染色。 */
        COLOR("color", "核心、外焰与光晕配色"),
        /** 波纹及明暗结节只改变视觉，不修改推进。 */
        MOTION("motion", "流动波纹与喷流亮节"),
        /** 时间受真实点火和燃尽窗口限制。 */
        TRANSITION("transition", "点火展开与燃尽收束"),
        /** 光晕外形与原有半径、亮度独立。 */
        HALO("halo", "广域光晕的柔和程度");

        /** 分类内稳定键，用于搜索及本地预设。 */
        public final String id;
        /** F8显示的中文组名。 */
        public final String title;

        Group(String id, String title) { this.id = id; this.title = title; }
    }

    /** 默认值只搬出现有常量；新增倍率为中性值，不自动写入覆盖。 */
    public enum Parameter {
        /** 长度相对武器flame_scale的倍率；默认1，稳态及启停均生效。 */
        LENGTH_SCALE(Group.SHAPE, "lengthScale", "喷焰长度倍率", "只拉长或缩短尾焰；仍以武器原有flame_scale为基础。", "倍", 1, 0.05, 5),
        /** 半径相对武器flame_scale的倍率；默认1，不联动长度。 */
        WIDTH_SCALE(Group.SHAPE, "widthScale", "喷焰粗细倍率", "只改变尾焰粗细，不改变长度、口径或照亮范围。", "倍", 1, 0.05, 5),
        /** 沿尾喷方向的视觉偏置，单位格；默认0，不移动弹体或JSON喷口。 */
        AXIAL_OFFSET(Group.SHAPE, "axialOffset", "喷焰沿尾轴偏移", "正值远离弹体，负值朝弹体内移；只移动喷焰画面。", "格", 0, -4, 4),
        /** 根部占最大轮廓半径的比例；默认0.22。 */
        NOZZLE_WIDTH(Group.SHAPE, "nozzleWidth", "喷口根部粗细比例", "根部相对整条喷流最大半径的比例；增大后根部更饱满。", "比例", 0.22, 0.01, 1),
        /** 轴向展开曲线指数；默认0.5，越大鼓起位置越靠后。 */
        EXPANSION_POWER(Group.SHAPE, "expansionPower", "喷流鼓起位置", "增大后最鼓的位置向尾端移动；减小后更靠近喷口。", "曲线系数", 0.5, 0.1, 3),
        /** 尾端收尖指数；默认0.45，越大收尖越早。 */
        TIP_POWER(Group.SHAPE, "tipPower", "尾端收尖强度", "越大越早变细，越小尾端越饱满；始终限制在真实喷流范围内。", "曲线系数", 0.45, 0.1, 4),
        /** 亮度沿喷流轴向的衰减指数；默认0.65，不改变几何范围。 */
        AXIAL_FADE(Group.SHAPE, "axialFade", "沿喷流亮度衰减", "越大越集中在喷口附近，越小亮焰延伸得越远。", "曲线系数", 0.65, 0.1, 4),
        /** 根部亮度渐入占全长比例；默认0.025，避免喷口硬切。 */
        ROOT_FADE(Group.SHAPE, "rootFade", "根部柔和过渡长度", "从喷口开始逐渐显亮的长度占比；增大后根部过渡更长。", "全长比例", 0.025, 0.001, 0.5),
        /** 白热核心半径倍率；默认1，原始半径平方占比仍为0.12。 */
        CORE_WIDTH_SCALE(Group.LAYERS, "coreWidthScale", "白热核心粗细倍率", "只改变内部亮芯粗细；需要配合原有核心亮度观察。", "倍", 1, 0.1, 2.5),
        /** 核心径向收边指数；默认4。 */
        CORE_EDGE_POWER(Group.LAYERS, "coreEdgePower", "核心边缘集中程度", "越大亮芯越集中、边缘越快变暗；不增加环境照明。", "曲线系数", 4, 0.25, 12),
        /** 核心形状增益；默认3，亮度峰值仍由原亮度参数控制。 */
        CORE_FILL(Group.LAYERS, "coreFill", "核心白热饱满程度", "增大后更多核心区域达到峰值；峰值亮度仍在原发动机分组调节。", "倍", 3, 0.1, 8),
        /** 外焰径向收边指数；默认2。 */
        OUTER_EDGE_POWER(Group.LAYERS, "outerEdgePower", "外焰边缘集中程度", "越大外焰边缘越快变暗，越小外层更饱满。", "曲线系数", 2, 0.25, 8),
        /** 核心红分量；默认沿用原橙色色比，强白热时会显示为白色。 */
        CORE_RED(Group.COLOR, "coreRed", "核心红色分量", "仅控制核心；高亮会趋白，调色时可先降低原有核心亮度。", "颜色分量", 1, 0, 1),
        /** 核心绿分量；默认0.38。 */
        CORE_GREEN(Group.COLOR, "coreGreen", "核心绿色分量", "与核心红、蓝共同配色；仅影响喷焰自身。", "颜色分量", 0.38, 0, 1),
        /** 核心蓝分量；默认0.07。 */
        CORE_BLUE(Group.COLOR, "coreBlue", "核心蓝色分量", "与核心红、绿共同配色；高亮时颜色会趋白。", "颜色分量", 0.07, 0, 1),
        /** 外焰红分量；默认1。 */
        OUTER_RED(Group.COLOR, "outerRed", "外焰红色分量", "只调核心之外的外焰，不联动核心及环境照明。", "颜色分量", 1, 0, 1),
        /** 外焰绿分量；默认0.38。 */
        OUTER_GREEN(Group.COLOR, "outerGreen", "外焰绿色分量", "与外焰红、蓝共同配色；光晕模糊输入会继承可见焰色。", "颜色分量", 0.38, 0, 1),
        /** 外焰蓝分量；默认0.07。 */
        OUTER_BLUE(Group.COLOR, "outerBlue", "外焰蓝色分量", "与外焰红、绿共同配色；不修改尾烟颜色。", "颜色分量", 0.07, 0, 1),
        /** 广域光晕红分量；默认1。 */
        HALO_RED(Group.COLOR, "haloRed", "广域光晕红色分量", "只改变喷焰周围的大范围柔光；受原广域光晕开关控制。", "颜色分量", 1, 0, 1),
        /** 广域光晕绿分量；默认0.38。 */
        HALO_GREEN(Group.COLOR, "haloGreen", "广域光晕绿色分量", "只改变大范围柔光，不联动方块、实体的环境照明颜色。", "颜色分量", 0.38, 0, 1),
        /** 广域光晕蓝分量；默认0.07。 */
        HALO_BLUE(Group.COLOR, "haloBlue", "广域光晕蓝色分量", "与广域光晕红、绿共同配色；不修改光晕半径。", "颜色分量", 0.07, 0, 1),
        /** 统一动画相位速度；默认13，0冻结流动。 */
        ANIMATION_SPEED(Group.MOTION, "animationSpeed", "喷流流动速度", "同时控制轮廓波纹和亮节的流动快慢；0冻结动画，暂停游戏也会冻结。", "速度系数", 13, 0, 60),
        /** 轮廓半径波动幅度；默认0.06，0固定轮廓。 */
        WAVE_STRENGTH(Group.MOTION, "waveStrength", "轮廓起伏强度", "越大轮廓的粗细变化越明显；0取消波纹，不扩大最大半径。", "比例", 0.06, 0, 0.5),
        /** 全长内波纹密度；默认31。 */
        WAVE_DENSITY(Group.MOTION, "waveDensity", "轮廓波纹密度", "越大同一长度内的粗细起伏越多；只调外轮廓。", "密度系数", 31, 0, 100),
        /** 全长内明暗结节密度；默认43。 */
        SHOCK_DENSITY(Group.MOTION, "shockDensity", "喷流亮节密度", "亮节是喷流沿轴向交替的亮暗结节，也叫激波纹；越大结节越密。", "密度系数", 43, 0, 120),
        /** 明暗结节对比倍率；默认1，0取消结节。 */
        SHOCK_CONTRAST(Group.MOTION, "shockContrast", "喷流亮节明暗对比", "0变为连续亮焰；增大后亮暗结节更明显，不改变推进力。", "比例", 1, 0, 1),
        /** 亮节聚集指数；默认6。 */
        SHOCK_SHARPNESS(Group.MOTION, "shockSharpness", "喷流亮节集中程度", "越大亮节越窄越集中，越小明暗变化越柔和。", "曲线系数", 6, 0.25, 16),
        /** 亮节相对统一动画的流动速度；默认0.22。 */
        SHOCK_DRIFT(Group.MOTION, "shockDrift", "亮节相对流动速度", "只调亮暗结节移动速度；轮廓波纹仍使用总流动速度，0固定结节。", "倍", 0.22, 0, 2),
        /** 点火展开时长，单位刻；默认沿用3刻，0立即展开。 */
        IGNITION_TICKS(Group.TRANSITION, "ignitionTicks", "点火展开时间", "从真实点火开始渐显；0立即出现，不提前点火。", "刻（20刻=1秒）", RVP_RocketPlumeParameters.IGNITION_TRANSITION_TICKS, 0, 100),
        /** 燃尽前收束时长，单位刻；默认沿用6刻，0不预先收束。 */
        BURNOUT_TICKS(Group.TRANSITION, "burnoutTicks", "燃尽前收束时间", "在真实燃尽之前渐隐；0保持到燃尽，绝不延长发动机燃烧期。", "刻（20刻=1秒）", RVP_RocketPlumeParameters.BURNOUT_TRANSITION_TICKS, 0, 100),
        /** 启停长度响应指数；默认1。 */
        LENGTH_RESPONSE(Group.TRANSITION, "lengthResponse", "启停长度收束曲线", "越大展开越慢、收束越早；只影响启停时的长度。", "曲线系数", 1, 0.1, 4),
        /** 启停半径响应指数；默认0.5。 */
        WIDTH_RESPONSE(Group.TRANSITION, "widthResponse", "启停粗细收束曲线", "越大启停时越细，越小越饱满；不改变稳态粗细。", "曲线系数", 0.5, 0.1, 4),
        /** 映射到画面后的显隐响应指数；默认1，避免高亮吃掉淡出。 */
        BRIGHTNESS_RESPONSE(Group.TRANSITION, "brightnessResponse", "启停明暗过渡曲线", "越大渐显越慢、渐隐越早；在亮度映射之后生效，白热核心也能淡出。", "曲线系数", 1, 0.1, 4),
        /** 广域光晕中心向外的指数衰减；默认6。 */
        HALO_FALLOFF(Group.HALO, "haloFalloff", "光晕中心集中程度", "越大光越集中在中心，越小越铺开；不改变世界半径。", "曲线系数", 6, 0.1, 20),
        /** 广域光晕边缘归零指数；默认2。 */
        HALO_EDGE_POWER(Group.HALO, "haloEdgePower", "光晕边缘收软程度", "越大边缘越早变暗；光晕仍在原有世界半径处完全归零。", "曲线系数", 2, 0.25, 8);

        /** F8所属分组。 */
        public final Group group;
        /** 搜索与预设使用的稳定键。 */
        public final String id;
        /** 用户可读的中文参数名称。 */
        public final String title;
        /** 调节作用、生效条件与限制。 */
        public final String description;
        /** 面板显示单位。 */
        public final String unit;
        /** 保持现有表现的起点值；不代表推荐调参值。 */
        public final double defaultValue;
        /** 可写入的下限，保证程序计算有限。 */
        public final double min;
        /** 可写入的上限，不扩大原渲染预算。 */
        public final double max;

        Parameter(Group group, String id, String title, String description, String unit,
                double defaultValue, double min, double max) {
            this.group = group;
            this.id = id;
            this.title = title;
            this.description = description;
            this.unit = unit;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }

        /** 正式绘制每帧读取当前值；未覆盖时继续使用起点值。 */
        public float value() { return OVERRIDES.getOrDefault(this, defaultValue).floatValue(); }
    }

    /** 面板和本地预设共用严格检查，非法输入不能进入逐像素计算。 */
    public static void set(Parameter parameter, double value) {
        if (parameter == null || !Double.isFinite(value) || value < parameter.min || value > parameter.max) {
            throw new IllegalArgumentException(parameter == null ? "尾焰参数不能为空"
                    : parameter.title + "需要" + parameter.min + "～" + parameter.max + " " + parameter.unit);
        }
        OVERRIDES.put(parameter, value);
    }

    /** 只保存主动设置项，打开面板不会填入数值或建立推荐预设。 */
    public static OptionalDouble override(Parameter parameter) {
        Double value = OVERRIDES.get(parameter);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** 恢复单项原始表现，不影响其它特效参数。 */
    public static void reset(Parameter parameter) { OVERRIDES.remove(parameter); }
    /** 清除本分类全部覆盖，不更改武器JSON或磁盘文件。 */
    public static void reset() { OVERRIDES.clear(); }
}
