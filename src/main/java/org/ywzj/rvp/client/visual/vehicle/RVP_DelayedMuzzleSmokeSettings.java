package org.ywzj.rvp.client.visual.vehicle;

import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 大口径延时炮口白烟的客户端参数；指令与 F8 面板共用同一份覆盖。
 *
 * <p>显式保存本地预设才写盘，不同步服务器。每次开火或预览取得不可变快照，
 * 调参不会改变已经排队或生成的烟。新增项默认沿用原有表现，寿命和初速接通原指令
 * 声明的默认范围。小口径即时烟和殉燃烟不读取这些参数。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_DelayedMuzzleSmokeSettings {
    /** 仅记录显式修改，包括用户主动输入与默认值相同的数值。 */
    private static final Map<Parameter, Double> OVERRIDES = new EnumMap<>(Parameter.class);

    private RVP_DelayedMuzzleSmokeSettings() { }

    /** 面板分组；稳定保存键与中文显示名分开。 */
    public enum Group {
        /** 延迟、持续时间及喷烟强弱变化。 */
        TIMING("timing", "时序与喷烟节奏"),
        /** 出生范围、烟量、口径比例和粒子预算。 */
        EMISSION("emission", "生成范围与烟量"),
        /** 喷出、上浮、空气阻力和连续旋涡。 */
        MOTION("motion", "喷出、上浮与气流"),
        /** 尺寸、浓度、颜色和环境受光。 */
        APPEARANCE("appearance", "大小、浓度与受光");

        /** 预设使用的稳定分组键。 */
        public final String id;
        /** 面板显示的中文分组名。 */
        public final String title;

        Group(String id, String title) { this.id = id; this.title = title; }
    }

    /** 所有实际接入生成或粒子更新的参数；速度内部仍采用 Minecraft 每刻单位。 */
    public enum Parameter {
        /** 最早开始出烟的等待时间，默认 10 刻。 */
        DELAY_MIN(Group.TIMING, "delayMin", "最短开火延迟", "开火后最早何时开始出烟。", "刻（20刻=1秒）", 10, 0, 600, true),
        /** 最晚开始出烟的等待时间，默认 20 刻。 */
        DELAY_MAX(Group.TIMING, "delayMax", "最长开火延迟", "开火后最晚何时开始出烟。", "刻（20刻=1秒）", 20, 0, 600, true),
        /** 最短连续喷烟时间，默认 15 刻。 */
        EMIT_DURATION_MIN(Group.TIMING, "emitDurationMin", "最短喷烟时间", "从第一缕烟开始计算的最短喷烟时长。", "刻（20刻=1秒）", 15, 1, 600, true),
        /** 最长连续喷烟时间，默认 20 刻。 */
        EMIT_DURATION_MAX(Group.TIMING, "emitDurationMax", "最长喷烟时间", "从第一缕烟开始计算的最长喷烟时长。", "刻（20刻=1秒）", 20, 1, 600, true),
        /** 单枚烟团最短寿命，默认 40 刻，逐枚随机。 */
        PARTICLE_LIFETIME_MIN(Group.TIMING, "particleLifetimeMin", "最短烟团寿命", "每枚烟团至少保留多久；逐枚随机，避免整批同时消失。", "刻（20刻=1秒）", 40, 4, 1200, true),
        /** 单枚烟团最长寿命，默认 60 刻。 */
        PARTICLE_LIFETIME_MAX(Group.TIMING, "particleLifetimeMax", "最长烟团寿命", "停止喷烟后已有烟仍继续漂散。", "刻（20刻=1秒）", 60, 4, 1200, true),
        /** 喷烟从弱到强的时间；0 保持立即达到全强度。 */
        EMIT_ATTACK_TICKS(Group.TIMING, "emitAttackTicks", "起烟渐强时间", "喷烟由弱到强的时间；0立即达到最大烟量。", "刻（20刻=1秒）", 0, 0, 40, true),
        /** 喷烟强度衰减幂次；0 禁用衰减。 */
        EMIT_DECAY_POWER(Group.TIMING, "emitDecayPower", "后段泄劲强度", "0均匀喷烟；越大越早减弱，同时减少烟量、喷出速度和浓度。", "系数", 0, 0, 6),
        /** 有衰减时末段相对烟量，默认 0。 */
        EMIT_END_STRENGTH(Group.TIMING, "emitEndStrength", "收尾保留烟量", "启用泄劲时，结束前保留的相对强度；0收尽，1不减弱。", "比例", 0, 0, 1),

        /** 峰值每刻烟团数，支持小数累积，默认 8。 */
        PARTICLES_PER_TICK(Group.EMISSION, "particlesPerTick", "峰值每刻烟团数", "小数跨刻累积，不直接舍弃；0停止生成新烟。", "枚/刻", 8, 0, 16),
        /** 出生点沿炮口向前覆盖的长度。 */
        SOURCE_LENGTH(Group.EMISSION, "sourceLength", "炮口前方生成长度", "烟出生时直接覆盖的长度；0只在炮口附近出生，再自行前进。", "格", 3, 0, 8),
        /** 出生点在炮管横截面上的随机半宽。 */
        SOURCE_RADIUS(Group.EMISSION, "sourceRadius", "炮口横向散布半宽", "烟出生点在炮管两条横向轴上的偏移上限。", "格", 0.2, 0, 2),
        /** 125 mm 为比例基准，0 禁用口径缩放。 */
        CALIBER_INFLUENCE(Group.EMISSION, "caliberInfluence", "口径对尺寸的影响", "以125毫米为基准，缩放烟团和出生范围；0各口径相同，1按口径比例缩放。", "比例", 0, 0, 1),
        /** 同一炮口同时存活的延时烟上限，不计殉燃烟。 */
        MAX_PARTICLES_PER_MUZZLE(Group.EMISSION, "maxParticlesPerMuzzle", "同炮口存活烟团上限", "连续开火共用预算；达到上限后跳过新烟，已有烟正常消散。", "枚", 256, 16, 512, true),
        /** 64～256 格逐渐减量，到最远端保留此比例。 */
        FAR_DENSITY(Group.EMISSION, "farDensity", "远处烟量保留比例", "64格内完整；64至256格逐渐减量，最远端保留此比例；1不减量。", "比例", 1, 0, 1),

        /** 最小出膛初速，沿用原指令默认 0.12 格/刻。 */
        AXIAL_SPEED_MIN(Group.MOTION, "axialSpeedMin", "最小向前初速", "沿当前炮管方向的最小初速度；面板显示格/秒，指令仍用格/刻。", "格/刻", 0.12, 0, 0.2),
        /** 最大出膛初速，沿用原指令默认 0.15 格/刻。 */
        AXIAL_SPEED_MAX(Group.MOTION, "axialSpeedMax", "最大向前初速", "沿当前炮管方向的最大初速度；随后受空气阻力影响。", "格/刻", 0.15, 0, 0.2),
        /** 世界竖直上浮目标速度，默认 0.08 格/刻。 */
        UPDRAFT(Group.MOTION, "updraft", "目标上浮速度", "烟逐渐接近的世界竖直速度，与炮管仰角无关。", "格/刻", 0.08, 0, 0.2),
        /** 每刻向上浮目标靠近的比例。 */
        UPDRAFT_RESPONSE(Group.MOTION, "updraftResponse", "上浮接管速度", "每刻消除多少竖直速度差；0保留出生竖直速度，1立即使用上浮目标。", "每刻比例", 0.02, 0, 1),
        /** 每刻消除的水平相对风速比例，对应原 0.965 保留率。 */
        HORIZONTAL_DRAG(Group.MOTION, "horizontalDrag", "水平空气阻力", "每刻消除多少水平相对风速；0保留惯性，1立即跟随设定风速。", "每刻比例", 0.035, 0, 1),
        /** 原有细微水平摆动，默认 0.003 格/刻²。 */
        SPREAD(Group.MOTION, "spread", "细微水平摆动", "小幅左右摆动的速度增量；可设0，单独观察连续旋涡。", "格/刻²", 0.003, 0, 0.05),
        /** 连续旋涡速度增量，0 关闭。 */
        CURL_STRENGTH(Group.MOTION, "curlStrength", "连续旋涡强度", "让同一片空气中的烟一起翻卷；0关闭，过大会快速散乱。", "格/刻²", 0, 0, 0.05),
        /** 连续旋涡的空间周期。 */
        CURL_SCALE(Group.MOTION, "curlScale", "烟卷空间尺度", "一轮旋涡的大致尺寸；越大越像整团翻卷，越小越细碎。", "格", 2, 0.1, 10),
        /** 连续旋涡随世界时间变化的倍率。 */
        CURL_RATE(Group.MOTION, "curlRate", "烟卷变化速度", "旋涡方向随时间变化的速度；0形成固定气流，烟仍会穿过气流。", "倍", 1, 0, 5),
        /** 出生时继承炮口运动速度的比例。 */
        VELOCITY_INHERITANCE(Group.MOTION, "velocityInheritance", "继承炮口运动速度", "0不继承，1带上炮口移动及转动速度；出生后不再绑在炮管上。", "比例", 0, 0, 1),
        /** 世界 X 轴的手动视觉风速。 */
        WIND_X(Group.MOTION, "windX", "东西方向风速", "正值向东（+X），负值向西；仅影响本地白烟，不改弹道。", "格/刻", 0, -0.2, 0.2),
        /** 世界 Z 轴的手动视觉风速。 */
        WIND_Z(Group.MOTION, "windZ", "南北方向风速", "正值向南（+Z），负值向北；通过水平空气阻力逐渐接管。", "格/刻", 0, -0.2, 0.2),
        /** 烟片随机正反转动的最大角速度。 */
        ROLL_SPEED(Group.MOTION, "rollSpeed", "烟片最大转动速度", "每片随机正转或反转；只转动现有贴图，不增加动画帧。", "度/秒", 14.323944878, 0, 90),

        /** 烟片基准半尺寸，沿用原先 0.65。 */
        PARTICLE_SIZE(Group.APPEARANCE, "particleSize", "烟团基准半尺寸", "面向相机的烟片半宽；还会乘出生倍率、膨胀及可选口径比例。", "格", 0.65, 0.1, 3),
        /** 出生半尺寸相对于基准的比例。 */
        INITIAL_SIZE_FACTOR(Group.APPEARANCE, "initialSizeFactor", "出生尺寸倍率", "烟出生时的尺寸相对于基准尺寸的倍数。", "倍", 0.7, 0.1, 2),
        /** 全寿命增加的基准尺寸倍数。 */
        GROWTH_AMOUNT(Group.APPEARANCE, "growthAmount", "全寿命膨胀增量", "寿命末尾额外增加多少基准尺寸；0不膨胀。", "倍", 1, 0, 4),
        /** 单枚烟片峰值不透明度。 */
        ALPHA(Group.APPEARANCE, "alpha", "单片峰值浓度", "0透明，1最浓；重叠仍会增浓，并受渐强、泄劲和扩散变淡影响。", "比例", 0.6, 0, 1),
        /** 单枚烟团渐入时间，0 禁用。 */
        FADE_IN_TICKS(Group.APPEARANCE, "fadeInTicks", "单团淡入时间", "单枚烟团从透明到正常浓度的时间；0立即显现。", "刻（20刻=1秒）", 0, 0, 40, true),
        /** 开始寿命淡出的比例。 */
        FADE_START(Group.APPEARANCE, "fadeStart", "开始淡出的寿命比例", "0.58表示寿命走过58%后淡出；1仅在寿命结束时移除。", "比例", 0.58, 0, 1),
        /** 膨胀时额外稀释浓度的幂次。 */
        EXPANSION_FADE(Group.APPEARANCE, "expansionFade", "膨胀时变薄强度", "一变大就逐渐变薄；0关闭，2近似按烟片面积增长稀释。", "系数", 0, 0, 3),
        /** 尺寸、浓度和灰度的随机偏差比例。 */
        VARIATION(Group.APPEARANCE, "variation", "烟团随机差异", "逐枚随机改变尺寸、浓度和灰度；0一致，0.15表示上下浮动15%。", "比例", 0.15, 0, 0.8),
        /** 中性灰白底色，0 黑、1 白。 */
        GREY(Group.APPEARANCE, "grey", "烟团灰白底色", "0黑、1白；保留贴图内部细节及RVP烟雾受光。", "灰度", 1, 0, 1),
        /** 原固定天空亮度与烟所在地原版光照的混合比例。 */
        ENVIRONMENT_LIGHT_MIX(Group.APPEARANCE, "environmentLightMix", "局部环境光照占比", "0保留原固定天空亮度，1读取烟所在地天空和方块光；RVP额外照亮仍保留。", "比例", 0, 0, 1);

        /** 所属面板分组。 */
        public final Group group;
        /** 指令和预设共用的稳定参数名。 */
        public final String commandName;
        /** 面板中文名称。 */
        public final String title;
        /** 作用和生效条件说明。 */
        public final String description;
        /** 内部数值及指令使用的单位。 */
        public final String unit;
        /** 不受覆盖影响的代码默认值。 */
        public final double defaultValue;
        /** 允许的最小值。 */
        public final double minValue;
        /** 允许的最大值。 */
        public final double maxValue;
        /** 是否必须按整刻或整枚输入。 */
        public final boolean integer;

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
        /** 指令与实际生成链始终读同一份覆盖。 */
        public double doubleValue() { return OVERRIDES.getOrDefault(this, defaultValue); }
        public int intValue() { return (int) doubleValue(); }
        /** 面板把每刻速度换为更直观的每秒速度；预设也保存面板单位。 */
        public double panelScale() { return unit.equals("格/刻") ? 20 : 1; }
        public String panelUnit() { return unit.equals("格/刻") ? "格/秒" : unit; }
        public String describe() {
            return commandName + "=" + String.format(Locale.ROOT, "%.4f", doubleValue())
                    + " " + unit + "（默认 " + defaultValue + "）";
        }

        /** 查找原指令及新增参数，不维护第二份名称清单。 */
        public static Parameter find(String name) {
            for (Parameter parameter : values()) {
                if (parameter.commandName.equals(name)) { return parameter; }
            }
            return null;
        }

        /** 只有 min/max 参数需要配对，其余参数互不联动。 */
        private Parameter peer() {
            return switch (this) {
                case DELAY_MIN -> DELAY_MAX;
                case DELAY_MAX -> DELAY_MIN;
                case EMIT_DURATION_MIN -> EMIT_DURATION_MAX;
                case EMIT_DURATION_MAX -> EMIT_DURATION_MIN;
                case PARTICLE_LIFETIME_MIN -> PARTICLE_LIFETIME_MAX;
                case PARTICLE_LIFETIME_MAX -> PARTICLE_LIFETIME_MIN;
                case AXIAL_SPEED_MIN -> AXIAL_SPEED_MAX;
                case AXIAL_SPEED_MAX -> AXIAL_SPEED_MIN;
                default -> null;
            };
        }

        private boolean isMinimum() { return name().endsWith("_MIN"); }

        private void validate(double value) {
            if (!Double.isFinite(value) || value < minValue || value > maxValue
                    || integer && value != Math.rint(value)) {
                throw new IllegalArgumentException(title + "需要" + (integer ? "整数 " : "数值 ")
                        + minValue + "～" + maxValue + " " + unit);
            }
        }
    }

    /** 指令严格校验范围及 min/max 关系，非法输入不修改任何参数。 */
    public static void set(Parameter parameter, double value) {
        if (parameter == null) { throw new IllegalArgumentException("参数不能为空"); }
        parameter.validate(value);
        Parameter peer = parameter.peer();
        if (peer != null && (parameter.isMinimum() ? value > peer.doubleValue() : value < peer.doubleValue())) {
            throw new IllegalArgumentException(parameter.commandName + "不能越过配对值 " + peer.doubleValue()
                    + " " + parameter.unit);
        }
        OVERRIDES.put(parameter, value);
    }

    /**
     * 面板和预设绑定：整刻四舍五入；越过区间另一端时同步推开另一端。
     * 允许通用保存器按任意键顺序恢复高于或低于默认区间的合法预设。
     */
    public static void setFromPanel(Parameter parameter, double value) {
        if (!Double.isFinite(value) || value < parameter.minValue || value > parameter.maxValue) {
            parameter.validate(value);
        }
        double next = parameter.integer ? Math.round(value) : value;
        parameter.validate(next);
        Parameter peer = parameter.peer();
        if (peer != null && (parameter.isMinimum() ? next > peer.doubleValue() : next < peer.doubleValue())) {
            OVERRIDES.put(peer, next);
        }
        OVERRIDES.put(parameter, next);
    }

    /** 读取真实覆盖，避免只打开面板就把默认值保存成覆盖。 */
    public static OptionalDouble override(Parameter parameter) {
        Double value = OVERRIDES.get(parameter);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** 单项恢复默认；只有与配对端冲突时才同步清理配对覆盖。 */
    public static void reset(Parameter parameter) {
        if (parameter == null) { return; }
        OVERRIDES.remove(parameter);
        Parameter peer = parameter.peer();
        if (peer != null && (parameter.isMinimum()
                ? parameter.doubleValue() > peer.doubleValue() : parameter.doubleValue() < peer.doubleValue())) {
            OVERRIDES.remove(peer);
        }
    }

    public static void resetAll() { OVERRIDES.clear(); }

    /** 包含两端的整数随机范围，供指令回归使用。 */
    public static int randomInclusive(Parameter min, Parameter max, RandomSource random) {
        return min.intValue() + random.nextInt(max.intValue() - min.intValue() + 1);
    }

    /** 连续速度随机范围，沿用原指令语义。 */
    public static double randomDoubleInclusive(Parameter min, Parameter max, RandomSource random) {
        return min.doubleValue() + random.nextDouble() * (max.doubleValue() - min.doubleValue());
    }

    /** 每次开火或预览保存整份参数，后续调整不会反向影响它。 */
    public static Snapshot snapshot() {
        Map<Parameter, Double> values = new EnumMap<>(Parameter.class);
        for (Parameter parameter : Parameter.values()) { values.put(parameter, parameter.doubleValue()); }
        return new Snapshot(values);
    }

    public record Snapshot(
            /** 完整不可变的内部单位参数表；同一发烟团共享一份。 */
            Map<Parameter, Double> values) {
        public Snapshot { values = Map.copyOf(values); }
        public double value(Parameter parameter) { return values.get(parameter); }
        public int integer(Parameter parameter) { return (int) value(parameter); }
        public int randomInt(Parameter min, Parameter max, RandomSource random) {
            return integer(min) + random.nextInt(integer(max) - integer(min) + 1);
        }
        public double randomDouble(Parameter min, Parameter max, RandomSource random) {
            return value(min) + random.nextDouble() * (value(max) - value(min));
        }
    }
}
