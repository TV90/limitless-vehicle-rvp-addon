package org.ywzj.rvp.client.visual;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 击毁长烟、短烟的客户端参数表；F8、指令和本地保存共用一份覆盖。
 *
 * <p>只扩充可调项，默认保留原有效果，不套用新的美术数值。长烟运动保留原指令的实时语义，
 * 短烟取得出生快照；受损发动机等共用近烟粒子的用途始终使用独立默认快照。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckSmokeDebugSettings {
    /** 长烟实时视图，不逐粒子复制完整参数表。 */
    public static final Values LIVE = Parameter::value;
    /** 其它近烟用途固定使用原始默认值，不读取击毁烟覆盖。 */
    private static final Snapshot DEFAULTS = capture(true);

    private RVP_WreckSmokeDebugSettings() { }

    /** 稳定分组键与中文名称分开，供通用F8面板自动生成控件。 */
    public enum Group {
        /** 击毁长烟 · 烟源与节奏。 */
        LONG_EMISSION("long_emission", "击毁长烟 · 烟源与节奏"),
        /** 击毁长烟 · 形状与消散。 */
        LONG_SHAPE("long_shape", "击毁长烟 · 形状与消散"),
        /** 击毁长烟 · 浓度与颜色。 */
        LONG_COLOR("long_color", "击毁长烟 · 浓度与颜色"),
        /** 击毁长烟 · 上升与气流。 */
        LONG_FLOW("long_flow", "击毁长烟 · 上升与气流"),
        /** 击毁短烟 · 烟源与节奏。 */
        NEAR_EMISSION("near_emission", "击毁短烟 · 烟源与节奏"),
        /** 击毁短烟 · 形状与消散。 */
        NEAR_SHAPE("near_shape", "击毁短烟 · 形状与消散"),
        /** 击毁短烟 · 浓度与颜色。 */
        NEAR_COLOR("near_color", "击毁短烟 · 浓度与颜色"),
        /** 击毁短烟 · 上浮与气流。 */
        NEAR_FLOW("near_flow", "击毁短烟 · 上浮与气流");

        /** 本地预设中的分组键。 */
        public final String id;
        /** F8中文分组名称。 */
        public final String title;

        Group(String id, String title) { this.id = id; this.title = title; }
    }

    /** 已实际接入烟源或粒子的参数；原16项指令名称、范围及默认值保留。 */
    public enum Parameter {
        /** 长烟生成间隔；单位刻（20刻=1秒），默认10。每轮生成一组深色核心和较浅外层，越小烟柱越连续。 */
        EMIT_PERIOD(Group.LONG_EMISSION, "emitPeriod", "长烟生成间隔", "每轮生成一组深色核心和较浅外层，越小烟柱越连续。", "刻（20刻=1秒）", 10, 5, 100, true),
        /** 长烟单团寿命；单位刻（20刻=1秒），默认460。每枚长烟保留多久；不改变残骸持续出烟的总时长。 */
        LIFETIME(Group.LONG_EMISSION, "lifetime", "长烟单团寿命", "每枚长烟保留多久；不改变残骸持续出烟的总时长。", "刻（20刻=1秒）", 460, 60, 1200, true),
        /** 烟源水平移动半径；单位格，默认1。烟源围绕车体中心移动的半径；0固定在中心。 */
        HORIZONTAL_OFFSET(Group.LONG_EMISSION, "horizontalOffset", "烟源水平移动半径", "烟源围绕车体中心移动的半径；0固定在中心。", "格", 1, 0, 16),
        /** 烟源移动周期；单位刻（20刻=1秒），默认80。烟源完成一次水平循环的时间。 */
        PHASE_PERIOD(Group.LONG_EMISSION, "phasePeriod", "烟源移动周期", "烟源完成一次水平循环的时间。", "刻（20刻=1秒）", 80, 10, 1200, true),
        /** 烟源南北方向相位差；单位度，默认90。东西和南北移动错开多少角度，决定烟源轨迹形状。 */
        Z_PHASE(Group.LONG_EMISSION, "zPhase", "烟源南北方向相位差", "东西和南北移动错开多少角度，决定烟源轨迹形状。", "度", 90, -360, 360),
        /** 长烟出生高度偏移；单位格，默认0。在原车体取点上抬高或降低烟源；负值向下。 */
        LONG_VERTICAL_OFFSET(Group.LONG_EMISSION, "longVerticalOffset", "长烟出生高度偏移", "在原车体取点上抬高或降低烟源；负值向下。", "格", 0, -8, 8),
        /** 长烟出生高度随机范围；单位格，默认0.25。在烟源基准高度上向上随机取点，避免完全重合。 */
        LONG_VERTICAL_JITTER(Group.LONG_EMISSION, "longVerticalJitter", "长烟出生高度随机范围", "在烟源基准高度上向上随机取点，避免完全重合。", "格", 0.25, 0, 4),
        /** 外层烟错位范围；单位倍，默认1。缩放外层相对核心的水平和竖直错位；0完全同心。 */
        LONG_OUTER_OFFSET_SCALE(Group.LONG_EMISSION, "longOuterOffsetScale", "外层烟错位范围", "缩放外层相对核心的水平和竖直错位；0完全同心。", "倍", 1, 0, 3),
        /** 长烟出生尺寸倍率；单位倍，默认1.75。缩放核心烟团，外层跟随；基础尺寸仍随车体变化。 */
        SIZE_SCALE(Group.LONG_SHAPE, "sizeScale", "长烟出生尺寸倍率", "缩放核心烟团，外层跟随；基础尺寸仍随车体变化。", "倍", 1.75, 0.1, 5),
        /** 每格高度膨胀比例；单位每格，默认0.05。每升高一格增加的出生半宽比例；0不膨胀。 */
        SIZE_GAIN(Group.LONG_SHAPE, "sizeGain", "每格高度膨胀比例", "每升高一格增加的出生半宽比例；0不膨胀。", "每格", 0.05, 0, 0.1),
        /** 外层烟尺寸倍率；单位倍，默认1。只缩放外层轮廓，不改变深色核心大小。 */
        OUTER_SIZE_SCALE(Group.LONG_SHAPE, "outerSizeScale", "外层烟尺寸倍率", "只缩放外层轮廓，不改变深色核心大小。", "倍", 1, 0.1, 3),
        /** 长烟成团时间；单位刻（20刻=1秒），默认48。使用现有贴图，从稀疏帧变为完整烟团；0立即成团。 */
        FORMATION_TICKS(Group.LONG_SHAPE, "formationTicks", "长烟成团时间", "使用现有贴图，从稀疏帧变为完整烟团；0立即成团。", "刻（20刻=1秒）", 48, 0, 200, true),
        /** 长烟开始淡出比例；单位寿命比例，默认0.85。寿命经过多少比例后开始变透明。 */
        FADE_START(Group.LONG_SHAPE, "fadeStart", "长烟开始淡出比例", "寿命经过多少比例后开始变透明。", "寿命比例", 0.85, 0, 0.98),
        /** 长烟单团淡入时间；单位刻（20刻=1秒），默认0。每团从透明到正常浓度的时间；0立即显现。 */
        LONG_FADE_IN_TICKS(Group.LONG_SHAPE, "longFadeInTicks", "长烟单团淡入时间", "每团从透明到正常浓度的时间；0立即显现。", "刻（20刻=1秒）", 0, 0, 120, true),
        /** 长烟膨胀变薄强度；单位系数，默认0。烟团变大时额外降低浓度；0关闭，越大稀释越快。 */
        LONG_EXPANSION_FADE(Group.LONG_SHAPE, "longExpansionFade", "长烟膨胀变薄强度", "烟团变大时额外降低浓度；0关闭，越大稀释越快。", "系数", 0, 0, 3),
        /** 长烟最大转动速度；单位度/秒，默认14.32394487827058。每片出生时随机正反转；只转动现有烟片。 */
        LONG_ROLL_SPEED(Group.LONG_SHAPE, "longRollSpeed", "长烟最大转动速度", "每片出生时随机正反转；只转动现有烟片。", "度/秒", 14.32394487827058, 0, 90),
        /** 核心烟浓度倍率；单位倍，默认1。缩放核心单片不透明度；重叠的烟还会叠加增浓。 */
        CORE_ALPHA_SCALE(Group.LONG_COLOR, "coreAlphaScale", "核心烟浓度倍率", "缩放核心单片不透明度；重叠的烟还会叠加增浓。", "倍", 1, 0, 2),
        /** 外层烟浓度倍率；单位倍，默认1。只调外层浓度，便于保留柔软轮廓。 */
        OUTER_ALPHA_SCALE(Group.LONG_COLOR, "outerAlphaScale", "外层烟浓度倍率", "只调外层浓度，便于保留柔软轮廓。", "倍", 1, 0, 2),
        /** 核心烟平均灰度；单位灰度，默认0.125。0黑、1白；保留贴图细节及原有轻微冷色。 */
        LONG_CORE_GREY(Group.LONG_COLOR, "longCoreGrey", "核心烟平均灰度", "0黑、1白；保留贴图细节及原有轻微冷色。", "灰度", 0.125, 0, 1),
        /** 外层烟平均灰度；单位灰度，默认0.23。外层可比核心稍浅，避免整根烟柱成为纯黑块。 */
        LONG_OUTER_GREY(Group.LONG_COLOR, "longOuterGrey", "外层烟平均灰度", "外层可比核心稍浅，避免整根烟柱成为纯黑块。", "灰度", 0.23, 0, 1),
        /** 长烟随机明暗差异；单位倍，默认1。原有逐团明暗差异的倍率；0统一灰度。 */
        LONG_GREY_VARIATION(Group.LONG_COLOR, "longGreyVariation", "长烟随机明暗差异", "原有逐团明暗差异的倍率；0统一灰度。", "倍", 1, 0, 3),
        /** 长烟随机浓度差异；单位倍，默认1。原有逐团浓度差异的倍率；0统一浓度。 */
        LONG_ALPHA_VARIATION(Group.LONG_COLOR, "longAlphaVariation", "长烟随机浓度差异", "原有逐团浓度差异的倍率；0统一浓度。", "倍", 1, 0, 3),
        /** 长烟局部受光占比；单位比例，默认0。0保留固定天空光，1读取当地天空和方块光；保留RVP额外照亮。 */
        LONG_ENVIRONMENT_LIGHT_MIX(Group.LONG_COLOR, "longEnvironmentLightMix", "长烟局部受光占比", "0保留固定天空光，1读取当地天空和方块光；保留RVP额外照亮。", "比例", 0, 0, 1),
        /** 长烟目标上升速度；单位格/刻，默认0.25。烟团逐渐接近的世界向上速度。 */
        UPDRAFT(Group.LONG_FLOW, "updraft", "长烟目标上升速度", "烟团逐渐接近的世界向上速度。", "格/刻", 0.25, 0.01, 1),
        /** 长烟基础东向风速；单位格/刻，默认0.057。原长烟的向东视觉风；保留原指令的非负范围。 */
        EAST_WIND(Group.LONG_FLOW, "eastWind", "长烟基础东向风速", "原长烟的向东视觉风；保留原指令的非负范围。", "格/刻", 0.057, 0, 0.5),
        /** 长烟高处增风比例；单位每格，默认0.04。每升高一格，东西和南北基础风增强多少比例。 */
        WIND_GAIN(Group.LONG_FLOW, "windGain", "长烟高处增风比例", "每升高一格，东西和南北基础风增强多少比例。", "每格", 0.04, 0, 0.1),
        /** 长烟微摆强度；单位格/刻²，默认0.0005。每团自身轻微左右摇摆，与连续整团翻卷分开。 */
        SWAY(Group.LONG_FLOW, "sway", "长烟微摆强度", "每团自身轻微左右摇摆，与连续整团翻卷分开。", "格/刻²", 0.0005, 0, 0.01),
        /** 长烟南北方向风速；单位格/刻，默认0。正值向南、负值向北；只改本地画面。 */
        LONG_SOUTH_WIND(Group.LONG_FLOW, "longSouthWind", "长烟南北方向风速", "正值向南、负值向北；只改本地画面。", "格/刻", 0, -0.5, 0.5),
        /** 长烟随风响应；单位每刻比例，默认0.04。每刻消除多少相对风速；0保留水平惯性，1立即跟风。 */
        LONG_HORIZONTAL_DRAG(Group.LONG_FLOW, "longHorizontalDrag", "长烟随风响应", "每刻消除多少相对风速；0保留水平惯性，1立即跟风。", "每刻比例", 0.04, 0, 1),
        /** 长烟上升响应；单位每刻比例，默认0.02。每刻向上升目标靠拢的比例；0保留出生竖直速度。 */
        LONG_UPDRAFT_RESPONSE(Group.LONG_FLOW, "longUpdraftResponse", "长烟上升响应", "每刻向上升目标靠拢的比例；0保留出生竖直速度。", "每刻比例", 0.02, 0, 1),
        /** 长烟微摆周期；单位刻（20刻=1秒），默认104.71975511965978。每团自身摇摆一周的时间，不控制烟源轨迹。 */
        LONG_SWAY_PERIOD(Group.LONG_FLOW, "longSwayPeriod", "长烟微摆周期", "每团自身摇摆一周的时间，不控制烟源轨迹。", "刻（20刻=1秒）", 104.71975511965978, 4, 1200),
        /** 长烟出生水平散速；单位格/刻，默认0.035。东西和南北各自在正负半范围内随机取速度。 */
        LONG_INITIAL_SPREAD(Group.LONG_FLOW, "longInitialSpread", "长烟出生水平散速", "东西和南北各自在正负半范围内随机取速度。", "格/刻", 0.035, 0, 0.5),
        /** 长烟最小向上初速；单位格/刻，默认0.025。刚出生时向上速度的下限，之后受目标上升速度接管。 */
        LONG_INITIAL_UPDRAFT_MIN(Group.LONG_FLOW, "longInitialUpdraftMin", "长烟最小向上初速", "刚出生时向上速度的下限，之后受目标上升速度接管。", "格/刻", 0.025, 0, 1),
        /** 长烟最大向上初速；单位格/刻，默认0.05。逐团随机向上初速的上限。 */
        LONG_INITIAL_UPDRAFT_MAX(Group.LONG_FLOW, "longInitialUpdraftMax", "长烟最大向上初速", "逐团随机向上初速的上限。", "格/刻", 0.05, 0, 1),
        /** 长烟连续翻卷强度；单位格/刻²，默认0。让邻近烟团随同一片空气翻卷；0关闭。 */
        LONG_CURL_STRENGTH(Group.LONG_FLOW, "longCurlStrength", "长烟连续翻卷强度", "让邻近烟团随同一片空气翻卷；0关闭。", "格/刻²", 0, 0, 0.02),
        /** 长烟翻卷尺度；单位格，默认6。一轮翻卷的大致尺寸，越大越像整团翻滚；仅翻卷开启后生效。 */
        LONG_CURL_SCALE(Group.LONG_FLOW, "longCurlScale", "长烟翻卷尺度", "一轮翻卷的大致尺寸，越大越像整团翻滚；仅翻卷开启后生效。", "格", 6, 0.1, 32),
        /** 长烟翻卷变化速度；单位倍，默认1。0保留固定空间气流，烟仍会穿过气流。 */
        LONG_CURL_RATE(Group.LONG_FLOW, "longCurlRate", "长烟翻卷变化速度", "0保留固定空间气流，烟仍会穿过气流。", "倍", 1, 0, 5),
        /** 短烟生成间隔；单位刻（20刻=1秒），默认5。残骸两轮短烟之间的时间，独立于长烟。 */
        NEAR_EMIT_PERIOD(Group.NEAR_EMISSION, "nearEmitPeriod", "短烟生成间隔", "残骸两轮短烟之间的时间，独立于长烟。", "刻（20刻=1秒）", 5, 1, 100, true),
        /** 短烟数量倍率；单位倍，默认1。基础数量仍随车体变化；小数数量按概率补足，0停止新短烟。 */
        NEAR_COUNT_SCALE(Group.NEAR_EMISSION, "nearCountScale", "短烟数量倍率", "基础数量仍随车体变化；小数数量按概率补足，0停止新短烟。", "倍", 1, 0, 3),
        /** 短烟最短单团寿命；单位刻（20刻=1秒），默认42。逐团随机寿命的下限，不改变残骸持续出烟时间。 */
        NEAR_LIFETIME_MIN(Group.NEAR_EMISSION, "nearLifetimeMin", "短烟最短单团寿命", "逐团随机寿命的下限，不改变残骸持续出烟时间。", "刻（20刻=1秒）", 42, 16, 400, true),
        /** 短烟最长单团寿命；单位刻（20刻=1秒），默认65。随机寿命上限，避免整批同时消失。 */
        NEAR_LIFETIME_MAX(Group.NEAR_EMISSION, "nearLifetimeMax", "短烟最长单团寿命", "随机寿命上限，避免整批同时消失。", "刻（20刻=1秒）", 65, 16, 400, true),
        /** 短烟出生范围倍率；单位倍，默认1。只缩放发动机内取点范围；0集中到中心。无发动机时缩放原车体取点范围。 */
        NEAR_SOURCE_SCALE(Group.NEAR_EMISSION, "nearSourceScale", "短烟出生范围倍率", "只缩放发动机内取点范围；0集中到中心。无发动机时缩放原车体取点范围。", "倍", 1, 0, 2),
        /** 短烟出生高度偏移；单位格，默认0。在发动机或车体取点上抬高、降低；不修改结构模型。 */
        NEAR_VERTICAL_OFFSET(Group.NEAR_EMISSION, "nearVerticalOffset", "短烟出生高度偏移", "在发动机或车体取点上抬高、降低；不修改结构模型。", "格", 0, -4, 4),
        /** 短烟基准尺寸倍率；单位倍，默认1。缩放原有半宽；车体和发动机大小仍参与基础计算。 */
        NEAR_SIZE_SCALE(Group.NEAR_SHAPE, "nearSizeScale", "短烟基准尺寸倍率", "缩放原有半宽；车体和发动机大小仍参与基础计算。", "倍", 1, 0.1, 4),
        /** 短烟膨胀起点倍率；单位倍，默认0.75。从首次更新起计算，相对于出生基准半宽的起始倍率。 */
        NEAR_INITIAL_SIZE_FACTOR(Group.NEAR_SHAPE, "nearInitialSizeFactor", "短烟膨胀起点倍率", "从首次更新起计算，相对于出生基准半宽的起始倍率。", "倍", 0.75, 0.1, 2),
        /** 短烟全寿命膨胀增量；单位倍，默认1.25。寿命末尾额外增加多少基准尺寸；0不按年龄膨胀。 */
        NEAR_GROWTH_AMOUNT(Group.NEAR_SHAPE, "nearGrowthAmount", "短烟全寿命膨胀增量", "寿命末尾额外增加多少基准尺寸；0不按年龄膨胀。", "倍", 1.25, 0, 5),
        /** 短烟单团淡入时间；单位刻（20刻=1秒），默认0。每团从透明到正常浓度的时间；0立即显现。 */
        NEAR_FADE_IN_TICKS(Group.NEAR_SHAPE, "nearFadeInTicks", "短烟单团淡入时间", "每团从透明到正常浓度的时间；0立即显现。", "刻（20刻=1秒）", 0, 0, 100, true),
        /** 短烟开始淡出比例；单位寿命比例，默认0.6。寿命经过多少比例后变淡；1到寿命末尾才移除。 */
        NEAR_FADE_START(Group.NEAR_SHAPE, "nearFadeStart", "短烟开始淡出比例", "寿命经过多少比例后变淡；1到寿命末尾才移除。", "寿命比例", 0.6, 0, 1),
        /** 短烟膨胀变薄强度；单位系数，默认0。0关闭；2近似按烟片面积增长稀释浓度。 */
        NEAR_EXPANSION_FADE(Group.NEAR_SHAPE, "nearExpansionFade", "短烟膨胀变薄强度", "0关闭；2近似按烟片面积增长稀释浓度。", "系数", 0, 0, 3),
        /** 短烟轮廓动画速度；单位倍，默认1。复用现有16帧；0固定首帧，大于1提早播到末帧并保持。 */
        NEAR_ANIMATION_RATE(Group.NEAR_SHAPE, "nearAnimationRate", "短烟轮廓动画速度", "复用现有16帧；0固定首帧，大于1提早播到末帧并保持。", "倍", 1, 0, 4),
        /** 短烟最大转动速度；单位度/秒，默认14.32394487827058。每片出生时随机正反转，不增加动画资源。 */
        NEAR_ROLL_SPEED(Group.NEAR_SHAPE, "nearRollSpeed", "短烟最大转动速度", "每片出生时随机正反转，不增加动画资源。", "度/秒", 14.32394487827058, 0, 90),
        /** 短烟浓度倍率；单位倍，默认1。缩放单片浓度，保留随机差异和贴图细节。 */
        NEAR_ALPHA_SCALE(Group.NEAR_COLOR, "nearAlphaScale", "短烟浓度倍率", "缩放单片浓度，保留随机差异和贴图细节。", "倍", 1, 0, 2),
        /** 短烟平均灰度；单位灰度，默认0.125。0黑、1白；保留原有轻微冷色，不影响受损发动机烟。 */
        NEAR_GREY(Group.NEAR_COLOR, "nearGrey", "短烟平均灰度", "0黑、1白；保留原有轻微冷色，不影响受损发动机烟。", "灰度", 0.125, 0, 1),
        /** 短烟随机明暗差异；单位倍，默认1。0统一灰度，1保留原有逐团差异。 */
        NEAR_GREY_VARIATION(Group.NEAR_COLOR, "nearGreyVariation", "短烟随机明暗差异", "0统一灰度，1保留原有逐团差异。", "倍", 1, 0, 3),
        /** 短烟随机浓度差异；单位倍，默认1。0统一浓度，1保留原有逐团差异。 */
        NEAR_ALPHA_VARIATION(Group.NEAR_COLOR, "nearAlphaVariation", "短烟随机浓度差异", "0统一浓度，1保留原有逐团差异。", "倍", 1, 0, 3),
        /** 短烟局部受光占比；单位比例，默认0。0保留固定天空光，1读取当地天空和方块光；不生成新光源。 */
        NEAR_ENVIRONMENT_LIGHT_MIX(Group.NEAR_COLOR, "nearEnvironmentLightMix", "短烟局部受光占比", "0保留固定天空光，1读取当地天空和方块光；不生成新光源。", "比例", 0, 0, 1),
        /** 短烟出生水平散速；单位格/刻，默认0.035。东西和南北分别在正负半范围内随机取速度。 */
        NEAR_INITIAL_SPREAD(Group.NEAR_FLOW, "nearInitialSpread", "短烟出生水平散速", "东西和南北分别在正负半范围内随机取速度。", "格/刻", 0.035, 0, 0.5),
        /** 短烟最小向上初速；单位格/刻，默认0.025。逐团随机向上初速的下限。 */
        NEAR_INITIAL_UPDRAFT_MIN(Group.NEAR_FLOW, "nearInitialUpdraftMin", "短烟最小向上初速", "逐团随机向上初速的下限。", "格/刻", 0.025, 0, 1),
        /** 短烟最大向上初速；单位格/刻，默认0.05。逐团随机向上初速的上限。 */
        NEAR_INITIAL_UPDRAFT_MAX(Group.NEAR_FLOW, "nearInitialUpdraftMax", "短烟最大向上初速", "逐团随机向上初速的上限。", "格/刻", 0.05, 0, 1),
        /** 短烟目标上浮速度；单位格/刻，默认0.075。烟逐渐接近的世界向上速度，默认等价于原有运动公式。 */
        NEAR_UPDRAFT(Group.NEAR_FLOW, "nearUpdraft", "短烟目标上浮速度", "烟逐渐接近的世界向上速度，默认等价于原有运动公式。", "格/刻", 0.075, 0, 0.5),
        /** 短烟上浮响应；单位每刻比例，默认0.02。每刻向上浮目标靠拢的比例；0保留竖直惯性。 */
        NEAR_UPDRAFT_RESPONSE(Group.NEAR_FLOW, "nearUpdraftResponse", "短烟上浮响应", "每刻向上浮目标靠拢的比例；0保留竖直惯性。", "每刻比例", 0.02, 0, 1),
        /** 短烟东西方向风速；单位格/刻，默认0.057。正值向东、负值向西；不改弹道或受损发动机烟。 */
        NEAR_EAST_WIND(Group.NEAR_FLOW, "nearEastWind", "短烟东西方向风速", "正值向东、负值向西；不改弹道或受损发动机烟。", "格/刻", 0.057, -0.5, 0.5),
        /** 短烟南北方向风速；单位格/刻，默认0。正值向南、负值向北。 */
        NEAR_SOUTH_WIND(Group.NEAR_FLOW, "nearSouthWind", "短烟南北方向风速", "正值向南、负值向北。", "格/刻", 0, -0.5, 0.5),
        /** 短烟随风响应；单位每刻比例，默认0.04。每刻消除多少相对风速；0保留水平惯性。 */
        NEAR_HORIZONTAL_DRAG(Group.NEAR_FLOW, "nearHorizontalDrag", "短烟随风响应", "每刻消除多少相对风速；0保留水平惯性。", "每刻比例", 0.04, 0, 1),
        /** 短烟微摆强度；单位格/刻²，默认0.0005。每团独立的轻微左右摇摆；0关闭。 */
        NEAR_SWAY(Group.NEAR_FLOW, "nearSway", "短烟微摆强度", "每团独立的轻微左右摇摆；0关闭。", "格/刻²", 0.0005, 0, 0.01),
        /** 短烟微摆周期；单位刻（20刻=1秒），默认104.71975511965978。每团左右摇摆一周的时间。 */
        NEAR_SWAY_PERIOD(Group.NEAR_FLOW, "nearSwayPeriod", "短烟微摆周期", "每团左右摇摆一周的时间。", "刻（20刻=1秒）", 104.71975511965978, 4, 1200),
        /** 短烟连续翻卷强度；单位格/刻²，默认0。让相邻烟团在同一片气流中一起翻卷；0关闭。 */
        NEAR_CURL_STRENGTH(Group.NEAR_FLOW, "nearCurlStrength", "短烟连续翻卷强度", "让相邻烟团在同一片气流中一起翻卷；0关闭。", "格/刻²", 0, 0, 0.02),
        /** 短烟翻卷尺度；单位格，默认2。一轮翻卷的大致尺寸；仅连续翻卷开启后生效。 */
        NEAR_CURL_SCALE(Group.NEAR_FLOW, "nearCurlScale", "短烟翻卷尺度", "一轮翻卷的大致尺寸；仅连续翻卷开启后生效。", "格", 2, 0.1, 16),
        /** 短烟翻卷变化速度；单位倍，默认1。0保留固定空间气流，只影响本地画面。 */
        NEAR_CURL_RATE(Group.NEAR_FLOW, "nearCurlRate", "短烟翻卷变化速度", "0保留固定空间气流，只影响本地画面。", "倍", 1, 0, 5);

        /** 所属F8分组。 */
        public final Group group;
        /** 指令和本地预设共用的参数键。 */
        public final String commandName;
        /** 中文参数名称。 */
        public final String title;
        /** 作用和生效条件。 */
        public final String description;
        /** 指令与内部计算使用的单位。 */
        public final String unit;
        /** 未显式修改时沿用的原始默认值。 */
        public final double defaultValue;
        /** 合法最小值。 */
        public final double minValue;
        /** 合法最大值。 */
        public final double maxValue;
        /** 是否按整刻输入。 */
        public final boolean integer;
        /** 仅保存明确修改；null表示沿用默认值。 */
        private Double overrideValue;

        Parameter(Group group, String name, String title, String description, String unit,
                  double value, double min, double max) {
            this(group, name, title, description, unit, value, min, max, false);
        }

        Parameter(Group group, String name, String title, String description, String unit,
                  double value, double min, double max, boolean integer) {
            this.group = group;
            this.commandName = name;
            this.title = title;
            this.description = description;
            this.unit = unit;
            this.defaultValue = value;
            this.minValue = min;
            this.maxValue = max;
            this.integer = integer;
        }

        public String commandName() { return commandName; }
        public double value() { return overrideValue == null ? defaultValue : overrideValue; }
        public int intValue() { return (int) value(); }
        public float floatValue() { return (float) value(); }
        public String unit() { return unit; }
        /** 只在面板边界换算；原指令速度继续使用格/刻。 */
        public double panelScale() { return unit.equals("格/刻") ? 20 : 1; }
        public String panelUnit() { return unit.equals("格/刻") ? "格/秒" : unit; }
        public OptionalDouble override() { return overrideValue == null ? OptionalDouble.empty() : OptionalDouble.of(overrideValue); }
        public boolean near() { return group.name().startsWith("NEAR_"); }

        /** 这些长烟项保留原有实时语义，其余只作用于新粒子或下一轮发射。 */
        public boolean liveForExisting() {
            return !near() && (group == Group.LONG_FLOW && this != LONG_INITIAL_SPREAD
                    && this != LONG_INITIAL_UPDRAFT_MIN && this != LONG_INITIAL_UPDRAFT_MAX
                    || this == SIZE_GAIN || this == FORMATION_TICKS || this == FADE_START
                    || this == LONG_FADE_IN_TICKS || this == LONG_EXPANSION_FADE || this == LONG_ENVIRONMENT_LIGHT_MIX);
        }

        /** 指令严格校验范围和上下限关系，非法输入不修改当前值。 */
        public void set(double next) {
            validate(next);
            Parameter peer = peer();
            if (peer != null && crosses(next, peer.value())) {
                throw new IllegalArgumentException(title + "不能越过配对值 " + format(peer.value()) + " " + unit);
            }
            overrideValue = next;
        }

        /** F8整刻输入四舍五入；越过配对值时联动另一端，支持任意预设键顺序。 */
        public void setFromPanel(double next) {
            if (!Double.isFinite(next) || next < minValue || next > maxValue) { validate(next); }
            double rounded = integer ? Math.round(next) : next;
            validate(rounded);
            Parameter peer = peer();
            if (peer != null && crosses(rounded, peer.value())) { peer.overrideValue = rounded; }
            overrideValue = rounded;
        }

        /** 清除单项覆盖；仅在默认值与配对端冲突时一并清理配对端。 */
        public void reset() {
            overrideValue = null;
            Parameter peer = peer();
            if (peer != null && crosses(value(), peer.value())) { peer.overrideValue = null; }
        }

        public String describe() {
            return commandName + "=" + format(value()) + " " + unit + "（默认 " + format(defaultValue) + "）";
        }

        public static Parameter find(String name) {
            for (Parameter parameter : values()) {
                if (parameter.commandName.equals(name)) { return parameter; }
            }
            return null;
        }

        private Parameter peer() {
            return switch (this) {
                case LONG_INITIAL_UPDRAFT_MIN -> LONG_INITIAL_UPDRAFT_MAX;
                case LONG_INITIAL_UPDRAFT_MAX -> LONG_INITIAL_UPDRAFT_MIN;
                case NEAR_LIFETIME_MIN -> NEAR_LIFETIME_MAX;
                case NEAR_LIFETIME_MAX -> NEAR_LIFETIME_MIN;
                case NEAR_INITIAL_UPDRAFT_MIN -> NEAR_INITIAL_UPDRAFT_MAX;
                case NEAR_INITIAL_UPDRAFT_MAX -> NEAR_INITIAL_UPDRAFT_MIN;
                default -> null;
            };
        }

        private boolean crosses(double next, double peer) { return name().endsWith("_MIN") ? next > peer : next < peer; }

        private void validate(double next) {
            if (!Double.isFinite(next) || next < minValue || next > maxValue || integer && next != Math.rint(next)) {
                throw new IllegalArgumentException(title + "需要" + (integer ? "整数 " : "数值 ")
                        + format(minValue) + "～" + format(maxValue) + " " + unit);
            }
        }

        private static String format(double number) { return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString(); }
    }

    /** 同一计算方法可读取长烟实时值或短烟出生快照。 */
    public interface Values {
        double value(Parameter parameter);
        default int integer(Parameter parameter) { return (int) value(parameter); }
    }

    public record Snapshot(
            /** 出生时完整参数表，只读，后续面板修改不会影响已有短烟。 */
            Map<Parameter, Double> values) implements Values {
        public Snapshot { values = Map.copyOf(values); }
        @Override public double value(Parameter parameter) { return values.get(parameter); }
    }

    /** 每轮短烟共享一份快照，不逐枚复制。 */
    public static Snapshot snapshot() { return capture(false); }
    /** 受损发动机等用途始终使用原始默认值。 */
    public static Snapshot defaults() { return DEFAULTS; }

    private static Snapshot capture(boolean defaults) {
        Map<Parameter, Double> values = new EnumMap<>(Parameter.class);
        for (Parameter parameter : Parameter.values()) {
            values.put(parameter, defaults ? parameter.defaultValue : parameter.value());
        }
        return new Snapshot(values);
    }

    /** 清除全部覆盖；本地预设写盘仍由通用F8保存器处理。 */
    public static void resetAll() {
        for (Parameter parameter : Parameter.values()) { parameter.overrideValue = null; }
    }
}
