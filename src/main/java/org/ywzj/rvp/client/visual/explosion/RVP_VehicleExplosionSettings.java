package org.ywzj.rvp.client.visual.explosion;

import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalDouble;

/** 程序大爆炸的唯一参数表；只供客户端预览读取，不参与伤害、实体或真实击毁事件。 */
public final class RVP_VehicleExplosionSettings {
    /** 仅记录用户明确修改的参数；打开面板不会产生覆盖。 */
    private static final Map<Parameter, Double> OVERRIDES = new EnumMap<>(Parameter.class);

    private RVP_VehicleExplosionSettings() { }

    /** 按美术调节顺序分组：先时序和轮廓，再配色、烟、热浪与预览控制。 */
    public enum Group {
        /** 整体时间和火球的世界尺寸。 */
        SHAPE("shape", "爆炸时序与体积"),
        /** 火焰本身的亮度与冷热层次。 */
        FIRE("fire", "白热核心与翻卷火焰"),
        /** 冷却后烟团的浓度、颜色和细节。 */
        SMOKE("smoke", "冷却黑烟与翻卷"),
        /** 背景折射与短促闪光，不改变玩家视角。 */
        AIR("air", "闪光与热浪扭曲"),
        /** 本地观察工具，不属于真实车辆配置。 */
        PREVIEW("preview", "预览位置、定格与画质");

        /** 保存配置时使用的稳定分组键。 */
        public final String id;
        /** 面板显示的中文组名。 */
        public final String title;

        Group(String id, String title) { this.id = id; this.title = title; }
    }

    /** 所有控件都连接逐帧绘制；时间以秒、尺寸以格表示，默认值是首版可预览的美术起点。 */
    public enum Parameter {
        /** 总寿命，默认6秒；循环、定格进度及最后淡出共用。 */
        DURATION(Group.SHAPE, "duration", "爆炸总时长", "从点火到黑烟散尽的时间；改变后当前预览立即采用新时长。", "秒", 6, 1, 15),
        /** 火球峰值半径，默认4.5格；包围范围和体积积分同步改变。 */
        RADIUS(Group.SHAPE, "radius", "火球峰值半径", "决定爆炸世界大小，走近会变大，转身后留在原地。", "格", 4.5, 0.5, 16),
        /** 达到峰值的时间，默认0.28秒；0.05秒为最小值，避免除零。 */
        EXPANSION(Group.SHAPE, "expansion", "火球膨胀时间", "从紧缩亮点快速撑开的时间；越短越有爆发感。", "秒", 0.28, 0.05, 2),
        /** 火焰燃烧时间，默认1.65秒；超过总时长的部分仍受总淡出限制。 */
        FIRE_DURATION(Group.SHAPE, "fireDuration", "明火冷却时间", "白热逐步转橙红再熄灭的时间；烟会在明火后期接上。", "秒", 1.65, 0.2, 6),
        /** 竖向尺度，默认1.15倍；不会移动爆心。 */
        STRETCH(Group.SHAPE, "stretch", "火球竖向拉伸", "1接近球形，增大后火焰与黑烟更高。", "倍", 1.15, 0.5, 2.5),
        /** 上升速度，默认0.85格/秒；按年龄抬升体积中心。 */
        RISE(Group.SHAPE, "rise", "烟火上升速度", "整团热气向上抬升的速度；不改变载具或弹体运动。", "格/秒", 0.85, 0, 4),
        /** 火焰显示曝光，默认3.8倍；只控制自发光，不创建环境灯。 */
        BRIGHTNESS(Group.FIRE, "brightness", "火焰自身亮度", "高值让炽热区域趋白；不会把整个屏幕曝光一起抬高。", "倍", 3.8, 0, 12),
        /** 热区宽度，默认0.52；增大后白热部分更饱满。 */
        CORE(Group.FIRE, "core", "白热核心占比", "控制火球内部白热区域，外缘仍保留橙红细节。", "比例", 0.52, 0, 1),
        /** 火焰绿色分量，默认0.34；红分量固定1。 */
        FIRE_GREEN(Group.FIRE, "fireGreen", "外焰绿色分量", "与蓝分量共同调外焰颜色；绿色较高时偏黄。", "颜色分量", 0.34, 0, 1),
        /** 火焰蓝色分量，默认0.035。 */
        FIRE_BLUE(Group.FIRE, "fireBlue", "外焰蓝色分量", "红分量固定1；增加绿、蓝会让外焰更接近浅色。", "颜色分量", 0.035, 0, 1),
        /** 轮廓起伏，默认0.38；控制大块分瓣和小块扰动。 */
        BILLOW(Group.FIRE, "billow", "火球分瓣起伏", "把整球轮廓打散成向外鼓起的火舌和烟瓣；0保留平滑体积。", "比例", 0.38, 0, 0.8),
        /** 连续流动速度，默认1.1；0冻结内部翻卷，仍保留膨胀和冷却。 */
        TURBULENCE(Group.FIRE, "turbulence", "内部翻卷速度", "相邻位置共享连续气流；不会每帧随机换形。", "倍", 1.1, 0, 4),
        /** 黑烟浓度，默认1.15；0完全关闭烟的吸收和散射。 */
        SMOKE_DENSITY(Group.SMOKE, "smokeDensity", "黑烟浓度", "决定冷却烟团遮住背景的程度；0只看明火与热浪。", "倍", 1.15, 0, 4),
        /** 烟的基础灰度，默认0.075；0黑、1白。 */
        SMOKE_GREY(Group.SMOKE, "smokeGrey", "烟团基础灰度", "烟表面另有向上受光与内部火光，避免变成平涂黑球。", "灰度", 0.075, 0, 0.6),
        /** 烟后期膨胀，默认0.65；火球峰值之后按寿命增大。 */
        SMOKE_EXPANSION(Group.SMOKE, "smokeExpansion", "黑烟后期膨胀", "相对火球半径继续展开，同时逐渐变薄；不生成粒子。", "倍", 0.65, 0, 2),
        /** 大块翻卷频率，默认2.8；在归一化体积内定义。 */
        NOISE_SCALE(Group.SMOKE, "noiseScale", "翻卷细节密度", "越大同一烟团里的纹理越细；随世界体积缩放，不跟随屏幕移动。", "系数", 2.8, 0.8, 7),
        /** 风向X速度，默认0；向东为正。 */
        WIND_X(Group.SMOKE, "windX", "东西方向漂移", "整团烟火向东或向西漂移；正值东，负值西。", "格/秒", 0, -4, 4),
        /** 风向Z速度，默认0；向南为正。 */
        WIND_Z(Group.SMOKE, "windZ", "南北方向漂移", "整团烟火向南或向北漂移；正值南，负值北。", "格/秒", 0, -4, 4),
        /** 初始闪光强度，默认0.8；是局部光团，不是全屏白片。 */
        FLASH(Group.AIR, "flash", "爆心瞬时闪光", "只在爆心周围短促发亮，仍受前景墙体遮挡；0关闭。", "倍", 0.8, 0, 3),
        /** 闪光持续时间，默认0.18秒。 */
        FLASH_DURATION(Group.AIR, "flashDuration", "闪光消退时间", "控制爆发瞬间白热亮团的持续时间；从第一帧平滑淡出。", "秒", 0.18, 0.05, 0.8),
        /** 折射位移，默认5像素；按当前画面实际像素换算。 */
        DISTORTION(Group.AIR, "distortion", "热浪扭曲强度", "像热空气一样扭动后方背景；仅重采样当前画面，0关闭。", "像素", 5, 0, 20),
        /** 外扩空气壳的时间，默认0.8秒。 */
        WAVE_DURATION(Group.AIR, "waveDuration", "空气波扩散时间", "透明空气壳从爆心扩散并淡出；与火焰冷却时间独立。", "秒", 0.8, 0.15, 2),
        /** 预览射线长度，默认28格；只在重新放置预览时读取。 */
        DISTANCE(Group.PREVIEW, "distance", "预览放置距离", "在这段距离内优先放到准星方块表面；没有命中就放在视线末端。重新预览生效。", "格", 28, 8, 96),
        /** 循环开关，默认关闭；由时间线读取，间隔固定0.6秒。 */
        LOOP(Group.PREVIEW, "loop", "循环重播", "开启后在同一位置反复播放，两轮之间停0.6秒；不会叠加多个爆炸。", "开关", 0, 0, 1),
        /** 定格开关，默认关闭；定格时形状、颜色、噪声时间全部冻结。 */
        HOLD(Group.PREVIEW, "hold", "定格观察", "开启后用下方进度固定整套效果；仍可走动、调颜色和改大小。", "开关", 0, 0, 1),
        /** 定格进度，默认0.08；0对应点火，1对应消散完成。 */
        PROGRESS(Group.PREVIEW, "progress", "定格时间进度", "只在定格观察开启时生效；0是点火，1是散尽。", "总寿命比例", 0.08, 0, 1),
        /** 积分步数，默认40；写入时取整，最大64。 */
        RAY_STEPS(Group.PREVIEW, "raySteps", "体积采样步数", "每个像素沿视线计算多少次烟火；越高越细腻，也越消耗显卡。", "步", 40, 16, 64),
        /** 稳定形态种子，默认7；写入时取整，重播同值保持相同形态。 */
        SEED(Group.PREVIEW, "seed", "爆炸形态种子", "相同数值在重播和定格时得到相同分瓣；换数值比较其它形态。", "整数", 7, 0, 4095);

        /** 面板所属分组。 */
        public final Group group;
        /** 本地保存的稳定参数键。 */
        public final String id;
        /** 用户看到的中文名称。 */
        public final String title;
        /** 参数用途及生效条件。 */
        public final String description;
        /** 用户输入的计量单位。 */
        public final String unit;
        /** 没有覆盖时采用的首版起点。 */
        public final double defaultValue;
        /** 合法输入下界。 */
        public final double min;
        /** 合法输入上界。 */
        public final double max;

        Parameter(Group group, String id, String title, String description, String unit,
                  double defaultValue, double min, double max) {
            this.group = group; this.id = id; this.title = title; this.description = description;
            this.unit = unit; this.defaultValue = defaultValue; this.min = min; this.max = max;
        }

        /** 只对开关使用严格的0或1；采样步数与种子允许输入小数后四舍五入。 */
        public boolean toggle() { return this == LOOP || this == HOLD; }
    }

    /** 单次读值，界面与快照共用，不能维护两份覆盖表。 */
    public static synchronized double value(Parameter parameter) {
        return OVERRIDES.getOrDefault(parameter, parameter.defaultValue);
    }

    /** 读取显式覆盖，未修改时为空，方便保存器省略默认项。 */
    public static synchronized OptionalDouble override(Parameter parameter) {
        Double result = OVERRIDES.get(parameter);
        return result == null ? OptionalDouble.empty() : OptionalDouble.of(result);
    }

    /** 写入前完整校验；非法输入不会污染正在播放的预览。 */
    public static synchronized void set(Parameter parameter, double value) {
        // 调用参数类型判断，业务入口也拒绝小数开关、无穷和越界。
        if (!Double.isFinite(value) || value < parameter.min || value > parameter.max
                || parameter.toggle() && value != 0 && value != 1) {
            throw new IllegalArgumentException(parameter.title + "：允许 " + parameter.min + "～" + parameter.max);
        }
        OVERRIDES.put(parameter, parameter == Parameter.SEED || parameter == Parameter.RAY_STEPS ? Math.round(value) : value);
    }

    /** 单项恢复默认，不制造默认值覆盖。 */
    public static synchronized void reset(Parameter parameter) { OVERRIDES.remove(parameter); }

    /** 分类恢复默认；已有预览下一帧读取新快照。 */
    public static synchronized void reset() { OVERRIDES.clear(); }

    /** 一帧只读取一份完整参数，避免体积形状和屏幕裁剪使用不同大小。 */
    public static synchronized Snapshot snapshot() {
        double[] values = new double[Parameter.values().length];
        for (Parameter parameter : Parameter.values()) {
            // 调用共用读值入口，默认与显式覆盖在渲染和面板内含义相同。
            values[parameter.ordinal()] = value(parameter);
        }
        return new Snapshot(values);
    }

    /** 不暴露内部数组的只读逐帧参数；不能由后续F8写入反向改变已有帧。 */
    public static final class Snapshot {
        /** 按参数枚举序号保存的私有值表。 */
        private final double[] values;

        private Snapshot(double[] values) { this.values = values; }

        /** 读取此帧有限且已校验的值。 */
        public float get(Parameter parameter) { return (float) values[parameter.ordinal()]; }
    }
}
