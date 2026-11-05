package org.ywzj.rvp.client.visual.cookoff;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 地面载具殉燃的客户端会话参数；F8、指令和真实粒子共用同一份业务覆盖。
 * 新增变化默认关闭或保持原值；继续使用现有贴图，不修改载具资产或服务器状态。
 */
public final class RVP_WreckCookoffSettings {
    /** 车顶生成点沿自身上方的最低偏置，单位格。 */
    public static final double ROOF_VERTICAL_OFFSET_MIN = -4;
    /** 车顶生成点沿自身上方的最高偏置，单位格。 */
    public static final double ROOF_VERTICAL_OFFSET_MAX = 4;
    /** 只记录用户明确修改的项；浏览面板不制造覆盖，也不写盘。 */
    private static final Map<Parameter, Double> OVERRIDES = new EnumMap<>(Parameter.class);

    private RVP_WreckCookoffSettings() { }

    /** 稳定分组键和中文名称；通用面板无需认识具体业务。 */
    public enum Group {
        /** 喷焰形状与消散。 */
        SHAPE("shape", "喷焰形状与消散"),
        /** 喷焰配色与亮度。 */
        COLOR("color", "喷焰配色与亮度"),
        /** 烟量与喷发节奏。 */
        RHYTHM("rhythm", "烟量与喷发节奏"),
        /** 上浮、翻卷与风。 */
        FLOW("flow", "上浮、翻卷与风"),
        /** 火星外形与冷却。 */
        SPARK_LOOK("spark_look", "火星外形与冷却"),
        /** 火星抛射与弹跳。 */
        SPARK_MOTION("spark_motion", "火星抛射与弹跳"),
        /** 轻重火星比例。 */
        SPARK_TYPES("spark_types", "轻重火星比例"),
        /** 殉燃炮口灰烟。 */
        MUZZLE("muzzle", "殉燃炮口灰烟");

        /** 预设使用的稳定分组键。 */
        public final String id;
        /** F8显示的中文名称。 */
        public final String title;
        Group(String id, String title) { this.id = id; this.title = title; }
    }

    /** 每个参数都连接真实生成或运动逻辑；内部速度继续使用格/刻。 */
    public enum Parameter {
        /** 车顶喷焰的最高目标；压力起伏只会降低当前高度。 默认 9 格。 */
        HEIGHT(Group.SHAPE, "height", "喷焰峰值高度", "车顶喷焰的最高目标；压力起伏只会降低当前高度。", "格", 9, 1, 16, false, false),
        /** 殉燃开始时由弱到强的时长。 默认 10 刻。 */
        GROWTH_TICKS(Group.SHAPE, "grow", "整体渐强时间", "殉燃开始时由弱到强的时长。", "刻", 10, 1, 120, true, false),
        /** 殉燃结束前的收缩时间；包含在原有殉燃总时长内。 默认 20 刻。 */
        SHRINK_TICKS(Group.SHAPE, "shrink", "整体收尾时间", "殉燃结束前的收缩时间；包含在原有殉燃总时长内。", "刻", 20, 1, 120, true, false),
        /** 沿舱盖自身朝外方向的最小喷出速度。 默认 0.8 格/刻。 */
        ROOF_SPEED_MIN(Group.SHAPE, "roof_speed_min", "最小轴向初速", "沿舱盖自身朝外方向的最小喷出速度。", "格/刻", 0.8, 0.05, 20, false, false),
        /** 沿舱盖自身朝外方向的最大喷出速度。 默认 1 格/刻。 */
        ROOF_SPEED_MAX(Group.SHAPE, "roof_speed_max", "最大轴向初速", "沿舱盖自身朝外方向的最大喷出速度。", "格/刻", 1, 0.05, 20, false, false),
        /** 喷焰根部半径相对于出口基础宽度的比例。 默认 0.12 倍。 */
        ROOF_CONE_BASE(Group.SHAPE, "roof_cone_base", "根部展开半径", "喷焰根部半径相对于出口基础宽度的比例。", "倍", 0.12, 0.02, 1.5, false, false),
        /** 喷焰远端半径相对于出口基础宽度的比例。 默认 0.95 倍。 */
        ROOF_CONE_TIP(Group.SHAPE, "roof_cone_tip", "末端展开半径", "喷焰远端半径相对于出口基础宽度的比例。", "倍", 0.95, 0.02, 2.5, false, false),
        /** 离喷口越远，横向展开越明显；0关闭。 默认 0.11 格/刻。 */
        ROOF_OUTWARD(Group.SHAPE, "roof_outward", "远端向外扩散速度", "离喷口越远，横向展开越明显；0关闭。", "格/刻", 0.11, 0, 0.5, false, false),
        /** 随机选择单片火焰大小的下限。 默认 1 倍。 */
        ROOF_SCALE_MIN(Group.SHAPE, "roof_scale_min", "最小火焰贴图倍率", "随机选择单片火焰大小的下限。", "倍", 1, 0.1, 3, false, false),
        /** 随机选择单片火焰大小的上限。 默认 3 倍。 */
        ROOF_SCALE_MAX(Group.SHAPE, "roof_scale_max", "最大火焰贴图倍率", "随机选择单片火焰大小的上限。", "倍", 3, 0.1, 3, false, false),
        /** 沿载具自身上方移动火焰出生点；负值向舱内。 默认 -0.5 格。 */
        ROOF_VERTICAL_OFFSET(Group.SHAPE, "roof_vertical_offset", "喷口朝外偏置", "沿载具自身上方移动火焰出生点；负值向舱内。", "格", -0.5, -4, 4, false, false),
        /** 单片平均不透明度，逐片仍有小幅随机差异。 默认 0.7 比例。 */
        FLAME_ALPHA(Group.SHAPE, "flame_alpha", "火焰单片浓度", "单片平均不透明度，逐片仍有小幅随机差异。", "比例", 0.7, 0, 1, false, false),
        /** 单片火焰最短可见时长；不是整车燃烧时间。 默认 8 刻。 */
        FLAME_LIFETIME_MIN(Group.SHAPE, "flame_lifetime_min", "最短火焰粒子寿命", "单片火焰最短可见时长；不是整车燃烧时间。", "刻", 8, 4, 120, true, false),
        /** 单片火焰最长可见时长。 默认 16 刻。 */
        FLAME_LIFETIME_MAX(Group.SHAPE, "flame_lifetime_max", "最长火焰粒子寿命", "单片火焰最长可见时长。", "刻", 16, 4, 120, true, false),
        /** 单片寿命经过多少比例后开始变淡；1寿命结束时移除。 默认 0.58 比例。 */
        FLAME_FADE_START(Group.SHAPE, "flame_fade_start", "火焰开始淡出比例", "单片寿命经过多少比例后开始变淡；1寿命结束时移除。", "比例", 0.58, 0, 1, false, false),
        /** 开启后到达目标高度便消散；关闭保留原有柱内循环。 默认 0 开关。 */
        NATURAL_TAIL(Group.SHAPE, "natural_tail", "末端自然消散", "开启后到达目标高度便消散；关闭保留原有柱内循环。", "开关", 0, 0, 1, false, true),
        /** 仅开启末端自然消散时生效；按距喷口的轴向进度开始变淡。 默认 0.7 比例。 */
        TIP_FADE_START(Group.SHAPE, "tip_fade_start", "末端消散起点", "仅开启末端自然消散时生效；按距喷口的轴向进度开始变淡。", "比例", 0.7, 0, 0.99, false, false),
        /** 0保留原橙黄随机色；1完全采用根部、中段、末端配色。 默认 0 比例。 */
        FLAME_GRADIENT(Group.COLOR, "flame_gradient", "火焰分层配色占比", "0保留原橙黄随机色；1完全采用根部、中段、末端配色。", "比例", 0, 0, 1, false, false),
        /** 缩放火焰本身的颜色亮度，不改变环境照亮。 默认 1 倍。 */
        FLAME_BRIGHTNESS(Group.COLOR, "flame_brightness", "火焰自身亮度", "缩放火焰本身的颜色亮度，不改变环境照亮。", "倍", 1, 0, 1, false, false),
        /** 红色固定为1；与蓝色共同调根部色，分层配色开启后生效。 默认 0.85 色彩分量。 */
        ROOT_GREEN(Group.COLOR, "root_green", "根部绿色分量", "红色固定为1；与蓝色共同调根部色，分层配色开启后生效。", "色彩分量", 0.85, 0, 1, false, false),
        /** 红色固定为1；绿色与蓝色越高，颜色越接近淡黄白。 默认 0.3 色彩分量。 */
        ROOT_BLUE(Group.COLOR, "root_blue", "根部蓝色分量", "红色固定为1；绿色与蓝色越高，颜色越接近淡黄白。", "色彩分量", 0.3, 0, 1, false, false),
        /** 分层配色中段的绿色；红色固定为1。 默认 0.5 色彩分量。 */
        MID_GREEN(Group.COLOR, "mid_green", "中段绿色分量", "分层配色中段的绿色；红色固定为1。", "色彩分量", 0.5, 0, 1, false, false),
        /** 分层配色中段的蓝色；红色固定为1。 默认 0.065 色彩分量。 */
        MID_BLUE(Group.COLOR, "mid_blue", "中段蓝色分量", "分层配色中段的蓝色；红色固定为1。", "色彩分量", 0.065, 0, 1, false, false),
        /** 分层配色末端的绿色；随位置和年龄逐渐过渡。 默认 0.17 色彩分量。 */
        TIP_GREEN(Group.COLOR, "tip_green", "末端绿色分量", "分层配色末端的绿色；随位置和年龄逐渐过渡。", "色彩分量", 0.17, 0, 1, false, false),
        /** 分层配色末端的蓝色；红色固定为1。 默认 0.01 色彩分量。 */
        TIP_BLUE(Group.COLOR, "tip_blue", "末端蓝色分量", "分层配色末端的蓝色；红色固定为1。", "色彩分量", 0.01, 0, 1, false, false),
        /** 共同控制火焰、火星和炮口烟；仍受原有单车和全局预算约束。 默认 1 倍。 */
        DENSITY(Group.RHYTHM, "density", "殉燃总粒子倍率", "共同控制火焰、火星和炮口烟；仍受原有单车和全局预算约束。", "倍", 1, 0, 2, false, false),
        /** 只调车顶火焰，不增加炮口烟或接缝火星。 默认 1 倍。 */
        FLAME_DENSITY(Group.RHYTHM, "flame_density", "车顶火焰数量倍率", "只调车顶火焰，不增加炮口烟或接缝火星。", "倍", 1, 0, 2, false, false),
        /** 只调舱盖和接缝火星；0停止生成新火星。 默认 1 倍。 */
        SPARK_DENSITY(Group.RHYTHM, "spark_density", "火星数量倍率", "只调舱盖和接缝火星；0停止生成新火星。", "倍", 1, 0, 2, false, false),
        /** 0均匀喷发；增加后联动改变喷焰高度、初速、浓度及火星数量。 默认 0 比例。 */
        PULSE_STRENGTH(Group.RHYTHM, "pulse_strength", "间歇喷发强度", "0均匀喷发；增加后联动改变喷焰高度、初速、浓度及火星数量。", "比例", 0, 0, 1, false, false),
        /** 一轮强弱变化的大致时间；仅启用间歇喷发后生效。 默认 30 刻。 */
        PULSE_PERIOD(Group.RHYTHM, "pulse_period", "喷发基本间隔", "一轮强弱变化的大致时间；仅启用间歇喷发后生效。", "刻", 30, 2, 200, false, false),
        /** 越大，强喷越短促；1较平缓，出口之间自动错开。 默认 2 系数。 */
        PULSE_SHARPNESS(Group.RHYTHM, "pulse_sharpness", "强喷持续集中程度", "越大，强喷越短促；1较平缓，出口之间自动错开。", "系数", 2, 1, 8, false, false),
        /** 连续扰动喷发节奏；0严格周期，增大后间隔不再整齐。 默认 0.35 比例。 */
        PULSE_IRREGULARITY(Group.RHYTHM, "pulse_irregularity", "喷发节奏不规则程度", "连续扰动喷发节奏；0严格周期，增大后间隔不再整齐。", "比例", 0.35, 0, 1, false, false),
        /** 持续抬升火焰的速度增量。 默认 0.0025 格/刻²。 */
        FLAME_BUOYANCY(Group.FLOW, "flame_buoyancy", "火焰上浮加速度", "持续抬升火焰的速度增量。", "格/刻²", 0.0025, 0, 0.05, false, false),
        /** 0全程服从喷口朝向；增大后离口越远越向天空抬升，侧翻时也生效。 默认 0 比例。 */
        WORLD_UP_MIX(Group.FLOW, "world_up_mix", "远端世界向上占比", "0全程服从喷口朝向；增大后离口越远越向天空抬升，侧翻时也生效。", "比例", 0, 0, 1, false, false),
        /** 让邻近火焰在同一片气流中一起翻卷；0关闭。 默认 0 格/刻²。 */
        CURL_STRENGTH(Group.FLOW, "curl_strength", "连续翻卷强度", "让邻近火焰在同一片气流中一起翻卷；0关闭。", "格/刻²", 0, 0, 0.05, false, false),
        /** 一轮翻卷的大致空间大小，越大越像整团运动。 默认 2 格。 */
        CURL_SCALE(Group.FLOW, "curl_scale", "火焰翻卷尺度", "一轮翻卷的大致空间大小，越大越像整团运动。", "格", 2, 0.1, 10, false, false),
        /** 气流随时间变化的倍率；0保留固定空间气流。 默认 1 倍。 */
        CURL_RATE(Group.FLOW, "curl_rate", "火焰翻卷变化速度", "气流随时间变化的倍率；0保留固定空间气流。", "倍", 1, 0, 5, false, false),
        /** 正值向东、负值向西；只影响殉燃火焰和火星，不改弹道。 默认 0 格/刻。 */
        WIND_X(Group.FLOW, "wind_x", "东西方向视觉风速", "正值向东、负值向西；只影响殉燃火焰和火星，不改弹道。", "格/刻", 0, -0.3, 0.3, false, false),
        /** 正值向南、负值向北；火星通过空气阻力逐渐跟随。 默认 0 格/刻。 */
        WIND_Z(Group.FLOW, "wind_z", "南北方向视觉风速", "正值向南、负值向北；火星通过空气阻力逐渐跟随。", "格/刻", 0, -0.3, 0.3, false, false),
        /** 中等火星的面片半宽；仍复用现有光斑贴图。 默认 0.16 格。 */
        SPARK_SIZE(Group.SPARK_LOOK, "spark_size", "火星基准半宽", "中等火星的面片半宽；仍复用现有光斑贴图。", "格", 0.16, 0.01, 0.5, false, false),
        /** 每颗出生时固定大小差异；0完全一致。 默认 0 比例。 */
        SPARK_SIZE_VARIATION(Group.SPARK_LOOK, "spark_size_variation", "火星尺寸随机差异", "每颗出生时固定大小差异；0完全一致。", "比例", 0, 0, 0.8, false, false),
        /** 缩放火星发光颜色，不增加环境灯。 默认 1 倍。 */
        SPARK_BRIGHTNESS(Group.SPARK_LOOK, "spark_brightness", "火星自身亮度", "缩放火星发光颜色，不增加环境灯。", "倍", 1, 0, 1, false, false),
        /** 新生火星的绿色；红色固定为1。 默认 0.9 色彩分量。 */
        SPARK_HOT_GREEN(Group.SPARK_LOOK, "spark_hot_green", "新生火星绿色分量", "新生火星的绿色；红色固定为1。", "色彩分量", 0.9, 0, 1, false, false),
        /** 新生火星的蓝色；绿色和蓝色越高越偏黄白。 默认 0.6 色彩分量。 */
        SPARK_HOT_BLUE(Group.SPARK_LOOK, "spark_hot_blue", "新生火星蓝色分量", "新生火星的蓝色；绿色和蓝色越高越偏黄白。", "色彩分量", 0.6, 0, 1, false, false),
        /** 启用冷却后的目标绿色分量，较低时偏暗红。 默认 0.08 色彩分量。 */
        SPARK_COLD_GREEN(Group.SPARK_LOOK, "spark_cold_green", "冷却火星绿色分量", "启用冷却后的目标绿色分量，较低时偏暗红。", "色彩分量", 0.08, 0, 1, false, false),
        /** 启用冷却后的目标蓝色分量。 默认 0.01 色彩分量。 */
        SPARK_COLD_BLUE(Group.SPARK_LOOK, "spark_cold_blue", "冷却火星蓝色分量", "启用冷却后的目标蓝色分量。", "色彩分量", 0.01, 0, 1, false, false),
        /** 0保留原固定颜色；1随年龄变色并将亮度逐渐降到15%。 默认 0 比例。 */
        SPARK_COOLING(Group.SPARK_LOOK, "spark_cooling", "火星冷却强度", "0保留原固定颜色；1随年龄变色并将亮度逐渐降到15%。", "比例", 0, 0, 1, false, false),
        /** 小于1较早变红；大于1保亮更久，末段冷却更快。 默认 1 系数。 */
        SPARK_COOLING_POWER(Group.SPARK_LOOK, "spark_cooling_power", "火星冷却曲线", "小于1较早变红；大于1保亮更久，末段冷却更快。", "系数", 1, 0.1, 5, false, false),
        /** 到此寿命比例后缩小并淡出；冷却配色可从出生后即开始。 默认 0.75 比例。 */
        SPARK_FADE_START(Group.SPARK_LOOK, "spark_fade_start", "火星开始淡出比例", "到此寿命比例后缩小并淡出；冷却配色可从出生后即开始。", "比例", 0.75, 0, 1, false, false),
        /** 按画面内速度拉伸现有贴图；0关闭，减速后拖影自动缩短。 默认 0 秒。 */
        SPARK_TRAIL_TIME(Group.SPARK_LOOK, "spark_trail_time", "火星拖影时间", "按画面内速度拉伸现有贴图；0关闭，减速后拖影自动缩短。", "秒", 0, 0, 0.1, false, false),
        /** 限制拖影附加长度；仅拖影时间大于0时生效。 默认 0.5 格。 */
        SPARK_TRAIL_MAX(Group.SPARK_LOOK, "spark_trail_max", "火星拖影最大长度", "限制拖影附加长度；仅拖影时间大于0时生效。", "格", 0.5, 0, 2, false, false),
        /** 沿现有舱盖或接缝采样方向喷出的初速下限。 默认 0.45 格/刻。 */
        SPARK_SPEED_MIN(Group.SPARK_MOTION, "spark_speed_min", "火星最小抛出速度", "沿现有舱盖或接缝采样方向喷出的初速下限。", "格/刻", 0.45, 0, 2, false, false),
        /** 初速上限；轻、中、重三类还会叠加自身倍率。 默认 0.95 格/刻。 */
        SPARK_SPEED_MAX(Group.SPARK_MOTION, "spark_speed_max", "火星最大抛出速度", "初速上限；轻、中、重三类还会叠加自身倍率。", "格/刻", 0.95, 0, 2, false, false),
        /** 中等火星最短寿命；保持多数较短、少数较长的分布。 默认 24 刻。 */
        SPARK_LIFETIME_MIN(Group.SPARK_MOTION, "spark_lifetime_min", "火星基准最短寿命", "中等火星最短寿命；保持多数较短、少数较长的分布。", "刻", 24, 4, 240, true, false),
        /** 中等火星最长寿命；较大碎屑可按类别倍率延长。 默认 80 刻。 */
        SPARK_LIFETIME_MAX(Group.SPARK_MOTION, "spark_lifetime_max", "火星基准最长寿命", "中等火星最长寿命；较大碎屑可按类别倍率延长。", "刻", 80, 4, 240, true, false),
        /** 向下的每刻速度增量；0不因重力下坠。 默认 0.04 格/刻²。 */
        SPARK_GRAVITY(Group.SPARK_MOTION, "spark_gravity", "火星重力加速度", "向下的每刻速度增量；0不因重力下坠。", "格/刻²", 0.04, 0, 0.12, false, false),
        /** 每刻消除多少水平相对风速；0保留惯性。 默认 0.04 每刻比例。 */
        SPARK_HORIZONTAL_DRAG(Group.SPARK_MOTION, "spark_horizontal_drag", "火星水平空气阻力", "每刻消除多少水平相对风速；0保留惯性。", "每刻比例", 0.04, 0, 1, false, false),
        /** 对竖直速度的每刻衰减；0保留原有抛物线。 默认 0 每刻比例。 */
        SPARK_VERTICAL_DRAG(Group.SPARK_MOTION, "spark_vertical_drag", "火星竖直空气阻力", "对竖直速度的每刻衰减；0保留原有抛物线。", "每刻比例", 0, 0, 1, false, false),
        /** 落地后保留多少竖直速度；0不弹起，1不损失。 默认 0.35 比例。 */
        SPARK_RESTITUTION(Group.SPARK_MOTION, "spark_restitution", "火星触地反弹速度比例", "落地后保留多少竖直速度；0不弹起，1不损失。", "比例", 0.35, 0, 1, false, false),
        /** 每次有效触地消耗多少水平速度。 默认 0.4 比例。 */
        SPARK_GROUND_FRICTION(Group.SPARK_MOTION, "spark_ground_friction", "火星触地水平摩擦", "每次有效触地消耗多少水平速度。", "比例", 0.4, 0, 1, false, false),
        /** 达到上限后停跳；0全部直接贴地滑行。 默认 3 次。 */
        SPARK_MAX_BOUNCES(Group.SPARK_MOTION, "spark_max_bounces", "火星最多弹跳次数", "达到上限后停跳；0全部直接贴地滑行。", "次", 3, 0, 6, true, false),
        /** 三项按总和归一化；更细、寿命更短、空气阻力更强。 默认 0 权重。 */
        SMALL_WEIGHT(Group.SPARK_TYPES, "small_weight", "细小火星权重", "三项按总和归一化；更细、寿命更短、空气阻力更强。", "权重", 0, 0, 100, false, false),
        /** 采用火星基准参数；三项都为0时回退中等火星。 默认 100 权重。 */
        MEDIUM_WEIGHT(Group.SPARK_TYPES, "medium_weight", "中等火星权重", "采用火星基准参数；三项都为0时回退中等火星。", "权重", 100, 0, 100, false, false),
        /** 数量少时作点缀；更大、寿命更长、空气阻力更弱。 默认 0 权重。 */
        HEAVY_WEIGHT(Group.SPARK_TYPES, "heavy_weight", "较大灼热碎屑权重", "数量少时作点缀；更大、寿命更长、空气阻力更弱。", "权重", 0, 0, 100, false, false),
        /** 炮管前方灰黑烟的生成范围；炮口仍不喷火星。 默认 3 格。 */
        MUZZLE_LENGTH(Group.MUZZLE, "length", "炮口烟轴向范围", "炮管前方灰黑烟的生成范围；炮口仍不喷火星。", "格", 3, 0.5, 8, false, false),
        /** 只调殉燃时炮口灰烟，不影响延时开火白烟。 默认 0.8 倍。 */
        MUZZLE_DENSITY(Group.MUZZLE, "muzzle_smoke_density", "炮口烟数量倍率", "只调殉燃时炮口灰烟，不影响延时开火白烟。", "倍", 0.8, 0, 2, false, false),
        /** 沿用现有静态贴图的灰黑烟片尺寸。 默认 0.65 格。 */
        MUZZLE_SIZE(Group.MUZZLE, "muzzle_smoke_size", "炮口烟基准半宽", "沿用现有静态贴图的灰黑烟片尺寸。", "格", 0.65, 0.1, 3, false, false),
        /** 单枚殉燃炮口烟的持续时间。 默认 36 刻。 */
        MUZZLE_LIFETIME(Group.MUZZLE, "muzzle_smoke_lifetime", "炮口烟寿命", "单枚殉燃炮口烟的持续时间。", "刻", 36, 4, 120, true, false),
        /** 沿当前炮管方向喷出的初速度。 默认 0.18 格/刻。 */
        MUZZLE_SPEED(Group.MUZZLE, "muzzle_smoke_axis_speed", "炮口烟向前初速", "沿当前炮管方向喷出的初速度。", "格/刻", 0.18, 0, 2, false, false),
        /** 离口后逐渐向世界上方漂移。 默认 0.08 格/刻。 */
        MUZZLE_UPDRAFT(Group.MUZZLE, "muzzle_smoke_updraft", "炮口烟上浮速度", "离口后逐渐向世界上方漂移。", "格/刻", 0.08, 0, 1, false, false),
        /** 原有微小水平速度扰动。 默认 0.003 格/刻²。 */
        MUZZLE_SPREAD(Group.MUZZLE, "muzzle_smoke_spread", "炮口烟水平摆动", "原有微小水平速度扰动。", "格/刻²", 0.003, 0, 0.25, false, false),
        /** 灰黑烟初始不透明度。 默认 0.6 比例。 */
        MUZZLE_ALPHA(Group.MUZZLE, "muzzle_smoke_alpha", "炮口烟单片浓度", "灰黑烟初始不透明度。", "比例", 0.6, 0, 1, false, false),
        /** 0偏黑、1偏白；保留原有灰黑色调范围。 默认 0.13 灰度。 */
        MUZZLE_GREY(Group.MUZZLE, "muzzle_smoke_grey", "炮口烟灰度", "0偏黑、1偏白；保留原有灰黑色调范围。", "灰度", 0.13, 0.02, 0.5, false, false);

        /** 所属面板分组。 */
        public final Group group;
        /** 指令与预设共用的稳定键，原有指令名称保持。 */
        public final String commandName;
        /** 面板中文名。 */
        public final String title;
        /** 实际作用和生效条件。 */
        public final String description;
        /** 业务层采用的单位。 */
        public final String unit;
        /** 不受会话覆盖影响的代码默认值。 */
        public final double defaultValue;
        /** 最小允许值。 */
        public final double minValue;
        /** 最大允许值。 */
        public final double maxValue;
        /** 是否将输入四舍五入为整刻或整次。 */
        public final boolean integer;
        /** 是否使用严格0/1开关。 */
        public final boolean toggle;

        Parameter(Group group, String commandName, String title, String description, String unit,
                  double defaultValue, double minValue, double maxValue, boolean integer, boolean toggle) {
            this.group = group;
            this.commandName = commandName;
            this.title = title;
            this.description = description;
            this.unit = unit;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.integer = integer;
            this.toggle = toggle;
        }

        /** 生成和指令读取同一份会话数值。 */
        public double value() { return OVERRIDES.getOrDefault(this, defaultValue); }
        /** 只在面板边界换算速度，预设保存相同的面板单位。 */
        public double panelScale() { return unit.equals("格/刻") ? 20 : 1; }
        public String panelUnit() { return unit.equals("格/刻") ? "格/秒" : unit; }

        /** 上下限各自仍有独立范围；圆锥根部/远端同样维持有序。 */
        private Parameter peer() {
            return switch (this) {
                case ROOF_SPEED_MIN -> ROOF_SPEED_MAX;
                case ROOF_SPEED_MAX -> ROOF_SPEED_MIN;
                case ROOF_CONE_BASE -> ROOF_CONE_TIP;
                case ROOF_CONE_TIP -> ROOF_CONE_BASE;
                case ROOF_SCALE_MIN -> ROOF_SCALE_MAX;
                case ROOF_SCALE_MAX -> ROOF_SCALE_MIN;
                case FLAME_LIFETIME_MIN -> FLAME_LIFETIME_MAX;
                case FLAME_LIFETIME_MAX -> FLAME_LIFETIME_MIN;
                case SPARK_SPEED_MIN -> SPARK_SPEED_MAX;
                case SPARK_SPEED_MAX -> SPARK_SPEED_MIN;
                case SPARK_LIFETIME_MIN -> SPARK_LIFETIME_MAX;
                case SPARK_LIFETIME_MAX -> SPARK_LIFETIME_MIN;
                default -> null;
            };
        }

        private boolean lowerBound() { return name().endsWith("_MIN") || this == ROOF_CONE_BASE; }

        /** 明确拒绝NaN、无穷、越界和非法开关，避免污染正在绘制的效果。 */
        private void validate(double value) {
            if (!Double.isFinite(value) || value < minValue || value > maxValue
                    || integer && value != Math.rint(value) || toggle && value != 0 && value != 1) {
                throw new IllegalArgumentException(title + "需要" + minValue + "～" + maxValue + " " + unit);
            }
        }
    }

    /** 整刻取整，越过配对端时联动；非对称范围限制在共同合法区间。 */
    public static void set(Parameter parameter, double value) {
        if (parameter == null) { throw new IllegalArgumentException("参数不能为空"); }
        if (!Double.isFinite(value) || value < parameter.minValue || value > parameter.maxValue) {
            parameter.validate(value);
        }
        double next = parameter.integer ? Math.round(value) : value;
        parameter.validate(next);
        Parameter peer = parameter.peer();
        if (peer != null && (parameter.lowerBound() ? next > peer.value() : next < peer.value())) {
            // 调用配对范围约束：远端可达2.5，但根部上限为1.5，不能反向推入非法值。
            double peerValue = Math.max(peer.minValue, Math.min(peer.maxValue, next));
            OVERRIDES.put(peer, peerValue);
            next = parameter.lowerBound() ? Math.min(next, peerValue) : Math.max(next, peerValue);
        }
        OVERRIDES.put(parameter, next);
    }

    /** 只有明确写入的项才保存，包含主动输入与默认值相同的值。 */
    public static OptionalDouble override(Parameter parameter) {
        Double value = OVERRIDES.get(parameter);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** 单项恢复默认；仅当配对端冲突时一并清除，保证撤销与预设恢复合法。 */
    public static void reset(Parameter parameter) {
        OVERRIDES.remove(parameter);
        Parameter peer = parameter.peer();
        if (peer != null && (parameter.lowerBound()
                ? parameter.value() > peer.value() : parameter.value() < peer.value())) {
            OVERRIDES.remove(peer);
        }
    }

    /** 重置整个殉燃分类，不接触其它特效和磁盘预设。 */
    public static void reset() { OVERRIDES.clear(); }

    /** 原有几何调用入口保留，内部统一写入业务覆盖。 */
    public static void setRoofAxisSpeedMin(double value) { set(Parameter.ROOF_SPEED_MIN, value); }
    public static void setRoofAxisSpeedMax(double value) { set(Parameter.ROOF_SPEED_MAX, value); }
    public static void setRoofConeBase(double value) { set(Parameter.ROOF_CONE_BASE, value); }
    public static void setRoofConeTip(double value) { set(Parameter.ROOF_CONE_TIP, value); }
    public static void setRoofTextureScaleMin(double value) { set(Parameter.ROOF_SCALE_MIN, value); }
    public static void setRoofTextureScaleMax(double value) { set(Parameter.ROOF_SCALE_MAX, value); }

    /** 原入口继续钳制喷口偏置，面板与指令仍先严格检查范围。 */
    public static void setRoofVerticalOffset(double value) {
        // 调用统一写入；非法输入保留原先归零语义。
        set(Parameter.ROOF_VERTICAL_OFFSET, Double.isFinite(value)
                ? Math.max(ROOF_VERTICAL_OFFSET_MIN, Math.min(ROOF_VERTICAL_OFFSET_MAX, value)) : 0);
    }

    /** 每刻只取一份不可变快照，所有新粒子共享，避免每颗复制整张参数表。 */
    public static Snapshot snapshot() {
        double[] values = Arrays.stream(Parameter.values()).mapToDouble(Parameter::value).toArray();
        return new Snapshot(values);
    }

    /** 不向外暴露可写数组；生成后改参数不会让已有粒子突然跳变。 */
    public static final class Snapshot {
        /** 出生时固定的完整内部单位数值。 */
        private final double[] values;
        private Snapshot(double[] values) { this.values = values.clone(); }
        public double value(Parameter parameter) { return values[parameter.ordinal()]; }
        public int integer(Parameter parameter) { return (int) value(parameter); }
        public boolean enabled(Parameter parameter) { return value(parameter) != 0; }
    }
}
