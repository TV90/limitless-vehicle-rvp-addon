package org.ywzj.rvp.weapon.visual;

import java.util.EnumMap;
import java.util.Map;

/** 三种冲击前沿的客户端会话参数；纯数值实现，便于独立验证，不读写 JSON。 */
public final class RVP_ShockwaveRuntimeTuning {
    /** 临时关闭地面冲击环；只允许修改代码恢复，调参指令不会绕过此开关。 */
    private static final boolean GROUND_RING_ENABLED = false;
    /** 临时关闭马赫杆墙体；保留算法和参数，便于后续恢复。 */
    private static final boolean MACH_STEM_ENABLED = false;
    /** 当前会话覆盖值；每次新爆炸复制快照，正在播放的效果不跳变。 */
    private static final Map<Group, Map<Parameter, Float>> VALUES = new EnumMap<>(Group.class);

    /** 可以独立调节的三种视觉组件。 */
    public enum Group {
        /** 向外扩张、带背景折射和凝结雾的球形空气层。 */ sphere,
        /** 地面冲击环。 */ ring,
        /** 马赫杆竖直墙体。 */ mach
    }

    /** 指令参数定义；NaN 默认值表示该组件不使用这个参数。 */
    public enum Parameter {
        /** 整段动画加速，范围不变，扩张、升起和淡出一起加快。 */
        speed("整体速度倍率", 0.05F, 100, 1.2F, 1.2F, 1.2F),
        /** 只加快扩张，寿命和淡出不跟随。 */
        expansionSpeed("仅扩张速度倍率", 0.05F, 100, 1, 1, 1),
        /** 最终视觉范围倍率，不改变伤害。 */
        radiusScale("范围系数（球体对应 meshSize；上限2048格）", 0, 20, 1.5F, 1, 1),
        /** 球体到达全范围、地面两项到达约95%范围所需的标准时间。 */
        expansionTicks("扩张基准时间（tick；球体另按尺寸缩放）", 1, 1200, 12, 15, 15),
        /** 各组件独立寿命。 */
        lifetime("基准寿命（tick；球体另按尺寸缩放）", 1, 2400, 12, 15, 15),
        /** 淡入时间，零表示立即显示。 */
        fadeInTicks("淡入时间（tick）", 0, 2400, 0, 0, 4F * 15 / 130),
        /** 开始淡出的时刻；超过寿命时在寿命末尾直接消失。 */
        fadeStartTicks("开始淡出时刻（tick）", 0, 2400, 0, 0, 0),
        /** 淡出指数，越大越早变淡。 */
        fadePower("淡出指数（球/环越大越晚变淡；墙越大越早变淡）", 0.1F, 10, 2, 3, 1),
        /** 球体空气层或地面几何的整体可见强度。 */
        opacity("可见强度倍率", 0, 1, 1, 1, 1),
        /** meshBrightness 默认 0.2；控制柔和凝结雾，不再给轮廓直接加白光。 */
        brightness("空气层凝结白雾强度", 0, 2, 0.2F, Float.NaN, Float.NaN),
        /** 厚度基数，乘以下面的压缩曲线后最多占外半径的 0.6。 */
        thickness("空气层相对厚度基数", 0.02F, 1, 1, Float.NaN, Float.NaN),
        /**  initialThicknessRatio；控制刚出现时空气层的厚度倍率。 */
        initialThicknessRatio("初始厚度倍率", 0, 2, 1, Float.NaN, Float.NaN),
        /**  finalThicknessRatio；控制扩张结束时空气层的厚度倍率。 */
        finalThicknessRatio("末期厚度倍率", 0, 2, 0.25F, Float.NaN, Float.NaN),
        /**  compressionSpeed；1 为匀速压薄，大于 1 时较晚变薄。 */
        compressionPower("厚度压缩曲线指数", 0.05F, 4, 1, Float.NaN, Float.NaN),
        /**  refraction；0 关闭背景扭曲，凝结白雾仍可单独显示。 */
        refraction("空气折射强度（背景扭曲）", 0, 1.5F, 1, Float.NaN, Float.NaN),
        /** 独立透明压力波前的力度；默认 1，0 仅保留原有宽厚空气层，不改变雾量或运动。 */
        pressureStrength("透明压力波前力度（0关闭加力层）", 0, 3, 1, Float.NaN, Float.NaN),
        /**  dispersion；控制折射边缘很轻微的颜色分离。 */
        dispersion("折射色散（边缘颜色分离）", 0, 0.2F, 0.06F, Float.NaN, Float.NaN),
        /** 球体的起始半径。 */
        initialRadius("起始半径（格）", 0, 64, 0, Float.NaN, Float.NaN),
        /** 的 meshExpansion：1 匀速，大于1先快后慢。 */
        expansionPower("球体扩张曲线指数（1匀速）", 0.05F, 4, 1, Float.NaN, Float.NaN),
        /** 的尘环速度全局倍率。 */
        particleSpeedScale("尘环平均初速度倍率", 0, 10, Float.NaN, 1.5F, 1.5F),
        /** CBC 尘环主体每 tick 保留的速度比例。 */
        damping("每tick保留速度比例（1不减速）", 0, 1, Float.NaN, 0.8F, 0.8F),
        /** 零使用用户选定的半球体积估算；非零允许校准实测方块数。 */
        sampledBlocks("采样方块数覆盖（0按半球体积估算）", 0, 1000000, Float.NaN, 0, 0),
        /** 地爆冲击环的相对宽度。 */
        width("地爆环宽占半径比例", 0, 1, Float.NaN, 0.09F, Float.NaN),
        /** 埋爆冲击环的相对宽度。 */
        buriedWidth("埋爆环宽占半径比例", 0, 1, Float.NaN, 0.25F, Float.NaN),
        /** 马赫杆高度倍率，乘算后仍受高度上下限约束。 */
        heightScale("墙高倍率", 0, 16, Float.NaN, Float.NaN, 1),
        /** 马赫杆高度下限。 */
        minHeight("墙高下限（格）", 0, 256, Float.NaN, Float.NaN, 2),
        /** 马赫杆高度上限。 */
        maxHeight("墙高上限（格）", 0, 256, Float.NaN, Float.NaN, 24),
        /** 马赫杆开始升起的时刻。 */
        riseDelayTicks("开始升墙的时刻（tick）", 0, 2400, Float.NaN, Float.NaN, 8F * 15 / 130),
        /** 从零到完整墙高的时间。 */
        riseTicks("升墙耗时（tick）", 0, 2400, Float.NaN, Float.NaN, 12F * 15 / 130),
        /** 墙体底部半径比例。 */
        outerScale("墙底半径比例", 0, 1, Float.NaN, Float.NaN, 0.94F),
        /** 墙体顶部半径比例，控制向内或向外倾斜。 */
        topScale("墙顶半径比例", 0, 1, Float.NaN, Float.NaN, 0.92F);

        /** 参数中文说明及单位。 */
        public final String description;
        /** 接受的数值下限。 */
        public final float min;
        /** 接受的数值上限。 */
        public final float max;
        /** 按组件顺序排列的默认值，不向外暴露数组。 */
        private final float[] defaults;

        Parameter(String description, float min, float max, float sphere, float ring, float mach) {
            this.description = description;
            this.min = min;
            this.max = max;
            defaults = new float[] {sphere, ring, mach};
        }

        /** 返回指定组件的默认值，NaN 表示不适用。 */
        public float defaultValue(Group group) { return defaults[group.ordinal()]; }

        /** 指令只展示和接受适用于当前组件的参数。 */
        public boolean supports(Group group) { return Float.isFinite(defaultValue(group)); }
    }

    private RVP_ShockwaveRuntimeTuning() { }

    /** 查询代码级启用状态；真实爆炸、预览和寿命统计使用同一入口。 */
    public static boolean enabled(Group group) {
        return switch (group) {
            case sphere -> true;
            case ring -> GROUND_RING_ENABLED;
            case mach -> MACH_STEM_ENABLED;
        };
    }

    /** 设置会话覆盖；拒绝非有限值以及不合理的墙高上下限。 */
    public static synchronized void set(Group group, Parameter parameter, float value) {
        if (!parameter.supports(group) || !Float.isFinite(value) || value < parameter.min || value > parameter.max) {
            throw new IllegalArgumentException("参数不适用或数值越界：" + parameter + "，允许 " + parameter.min + "～" + parameter.max);
        }
        Snapshot current = snapshot();
        if (parameter == Parameter.minHeight && value > current.get(group, Parameter.maxHeight)
                || parameter == Parameter.maxHeight && value < current.get(group, Parameter.minHeight)) {
            throw new IllegalArgumentException("墙高下限不能大于上限；请先调整另一端。");
        }
        VALUES.computeIfAbsent(group, ignored -> new EnumMap<>(Parameter.class)).put(parameter, value);
    }

    /** 恢复全部组件的代码默认值。 */
    public static synchronized void reset() { VALUES.clear(); }

    /** 恢复单个组件的代码默认值。 */
    public static synchronized void reset(Group group) { VALUES.remove(group); }

    /** 为下一次爆炸复制参数，避免调参影响已经开始的时间线。 */
    public static synchronized Snapshot snapshot() {
        Map<Group, Map<Parameter, Float>> copy = new EnumMap<>(Group.class);
        for (Group group : Group.values()) {
            Map<Parameter, Float> parameters = new EnumMap<>(Parameter.class);
            for (Parameter parameter : Parameter.values()) {
                if (parameter.supports(group)) {
                    parameters.put(parameter, VALUES.getOrDefault(group, Map.of())
                            .getOrDefault(parameter, parameter.defaultValue(group)));
                }
            }
            copy.put(group, Map.copyOf(parameters));
        }
        return new Snapshot(Map.copyOf(copy), 24, 1);
    }

    /** 一次爆炸持有的不可变参数与曲线算法。 */
    public static final class Snapshot {
        /** 三组完整数值，所有映射都不可修改。 */
        private final Map<Group, Map<Parameter, Float>> values;
        /** 本次事件的原始爆炸半径，用于尺寸公式及尘环方块数估算。 */
        private final float explosionRadius;
        /** 本次事件的整体视觉尺寸，不改变基础时间缩放。 */
        private final float visualScale;

        private Snapshot(Map<Group, Map<Parameter, Float>> values, float explosionRadius, float visualScale) {
            this.values = values;
            this.explosionRadius = explosionRadius;
            this.visualScale = visualScale;
        }

        /** 把同一份会话快照绑定到具体爆炸，所有组件共享实际半径，不新增消息字段。 */
        public Snapshot forExplosion(float radius, float scale) {
            if (!Float.isFinite(radius) || radius < 0 || !Float.isFinite(scale) || scale < 0) {
                throw new IllegalArgumentException("爆炸半径和视觉倍率必须是非负有限值");
            }
            return new Snapshot(values, radius, scale);
        }

        /** 取得当前组件适用的参数。 */
        public float get(Group group, Parameter parameter) { return values.get(group).get(parameter); }

        /** 把标准年龄换算为组件年龄；speed 同时压缩扩张和淡出。 */
        public float age(Group group, float standardAge) {
            // 调用尺寸时间换算，仅球体遵守尺寸越大、播放越久规则。
            return standardAge * get(group, Parameter.speed) / timeScale(group);
        }

        /** 球体按尺寸缩放寿命；环和墙使用固定粒子寿命。 */
        private float timeScale(Group group) {
            // 调用数值参照，保持尺寸计算与寿命缩放一致。
            return group == Group.sphere ? RVP_ShockwaveReference.sphereTimeScale(
                    RVP_ShockwaveReference.sphereRadius(explosionRadius, get(group, Parameter.radiusScale))) : 1;
        }

        /** 返回组件扩张比例；球体使用曲线，环和墙使用粒子逐tick减速累计位移。 */
        public float expansion(Group group, float standardAge) {
            float progress = age(group, standardAge) * get(group, Parameter.expansionSpeed)
                    / get(group, Parameter.expansionTicks);
            if (group == Group.sphere) {
                return 1 - (float) Math.pow(1 - clamp(progress), get(group, Parameter.expansionPower));
            }
            // 调用粒子位移累加，将原先的指数近似改为有依据的逐tick减速运动。
            float damping = get(group, Parameter.damping);
            return RVP_ShockwaveReference.ringDistance(1, damping, clamp(progress) * RVP_ShockwaveReference.RING_TICKS)
                    / RVP_ShockwaveReference.ringDistance(1, damping, RVP_ShockwaveReference.RING_TICKS);
        }

        /** 按独立淡入、淡出、寿命与可见强度计算透明度。 */
        public float alpha(Group group, float standardAge) {
            float age = age(group, standardAge);
            float lifetime = get(group, Parameter.lifetime);
            if (age < 0 || age >= lifetime) { return 0; }
            float fadeIn = get(group, Parameter.fadeInTicks);
            float start = get(group, Parameter.fadeStartTicks);
            // 球体默认按扩张曲线淡出；显式 expansionSpeed 仍只调运动，不偷偷改变淡出时间。
            float progress = group == Group.sphere
                    ? 1 - (float) Math.pow(1 - clamp(age / lifetime), get(group, Parameter.expansionPower)) : age / lifetime;
            float startRatio = start / lifetime;
            float fadeProgress = progress <= startRatio ? 0 : clamp((progress - startRatio) / (1 - startRatio));
            float power = get(group, Parameter.fadePower);
            float fade = group == Group.mach ? (float) Math.pow(1 - fadeProgress, power)
                    : 1 - (float) Math.pow(fadeProgress, power);
            return (fadeIn == 0 ? 1 : clamp(age / fadeIn)) * fade * get(group, Parameter.opacity);
        }

        /** 当前尘环/墙体平均位移（格）；用同一组默认值时二者始终同步。 */
        public float groundRadius(Group group, float standardAge) {
            // 调用用户确认的估算方案，或使用指令提供的实测采样数。
            float override = get(group, Parameter.sampledBlocks);
            int blocks = override == 0 ? RVP_ShockwaveReference.estimateBlocks(explosionRadius) : Math.round(override);
            // 调用三档初速度和 CBC 烟粒子衰减的数值换算。
            float initialSpeed = RVP_ShockwaveReference.ringSpeed(blocks, get(group, Parameter.particleSpeedScale));
            float distance = RVP_ShockwaveReference.ringDistance(initialSpeed, get(group, Parameter.damping),
                    RVP_ShockwaveReference.RING_TICKS) * expansion(group, standardAge);
            return Math.min(2048, distance * get(group, Parameter.radiusScale) * visualScale);
        }

        /** 返回球壳当前半径，默认按从零半径扩张，可额外指定起始半径。 */
        public float sphereRadius(float standardAge) {
            // 调用幂次半径公式，不再使用 RVP 原有的2.5倍线性半径。
            float radius = RVP_ShockwaveReference.sphereRadius(explosionRadius, get(Group.sphere, Parameter.radiusScale));
            return Math.min(2048, get(Group.sphere, Parameter.initialRadius)
                    + radius * visualScale * expansion(Group.sphere, standardAge));
        }

        /** 返回当前空气层厚度占外半径的比例；使用光影合成路径的压薄公式。 */
        public float sphereThickness(float standardAge) {
            float progress = clamp(age(Group.sphere, standardAge) / get(Group.sphere, Parameter.lifetime));
            float initial = get(Group.sphere, Parameter.initialThicknessRatio);
            float end = get(Group.sphere, Parameter.finalThicknessRatio);
            float ratio = initial + (end - initial)
                    * (float) Math.pow(progress, get(Group.sphere, Parameter.compressionPower));
            return Math.max(0, Math.min(0.6F, get(Group.sphere, Parameter.thickness) * ratio));
        }

        /**
         * 透明压力波前的折射强度；在寿命前 8% 内迅速建立推力，随后由强到弱释放。
         * 只塑造折射节奏，整体透明度仍由调用方使用同一 alpha 曲线控制，不延长寿命。
         */
        public float pressureRefraction(float standardAge) {
            // 调用同一尺寸时间换算，使小爆炸、整体提速和自定义事件都保持相同的推力节奏。
            float progress = age(Group.sphere, standardAge) / get(Group.sphere, Parameter.lifetime);
            if (progress <= 0 || progress >= 1) { return 0; }
            float attack = clamp(progress / 0.08F);
            attack = attack * attack * (3 - 2 * attack);
            float release = 1.35F + 2.65F * (float) Math.exp(-progress / 0.24F);
            return get(Group.sphere, Parameter.refraction) * get(Group.sphere, Parameter.pressureStrength)
                    * attack * release;
        }

        /** 压力集中在最外侧的薄空气层；始终包含在宽厚雾层内，不扩大既有半径。 */
        public float pressureThickness(float standardAge) {
            // 调用正式厚度曲线，薄波前也随扩张压缩，极薄或零厚度配置不能被自动加厚。
            float body = sphereThickness(standardAge);
            return Math.min(body, Math.max(0.035F, Math.min(0.14F, body * 0.22F)));
        }

        /** 马赫杆随时间升起并淡出；透明度倍率不改变墙高。 */
        public float wallHeight(float baseRadius, float solidity, float standardAge) {
            float height = baseRadius * (0.15F + 0.65F * solidity) * get(Group.mach, Parameter.heightScale);
            height = Math.max(get(Group.mach, Parameter.minHeight), Math.min(get(Group.mach, Parameter.maxHeight), height));
            float elapsed = age(Group.mach, standardAge) - get(Group.mach, Parameter.riseDelayTicks);
            float rise = get(Group.mach, Parameter.riseTicks);
            float opacity = get(Group.mach, Parameter.opacity);
            return height * (elapsed < 0 ? 0 : rise == 0 ? 1 : clamp(elapsed / rise))
                    * (opacity == 0 ? 0 : alpha(Group.mach, standardAge) / opacity);
        }

        /** 换算实际持续 tick；允许调慢或延长时继续播放，迟到包仍从正确年龄开始。 */
        public int duration(Group group, int baseDuration) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1, Math.ceil(
                    (double) get(group, Parameter.lifetime) * timeScale(group) / get(group, Parameter.speed) * baseDuration / 220)));
        }

        /** 整个实例的寿命同时覆盖原云团与调参后的三种前沿。 */
        public int totalDuration(int baseDuration) {
            // 调用前沿寿命聚合，完整威尔逊实例继续保留原云团时间线。
            return Math.max(baseDuration, frontDuration(baseDuration));
        }

        /** 纯前沿事件结束即回收，避免快速效果消失后仍占用原来的220tick实例预算。 */
        public int frontDuration(int baseDuration) {
            int duration = 0;
            for (Group group : Group.values()) {
                // 调用代码开关检查，关闭的地面几何不能延长实例占用时间。
                if (enabled(group)) { duration = Math.max(duration, duration(group, baseDuration)); }
            }
            return duration;
        }
    }

    /** 将曲线进度限制到零至一。 */
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
