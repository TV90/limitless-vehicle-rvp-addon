package org.ywzj.rvp.light;

/** 爆炸/炮口整盏光源的游戏刻时间线；照亮和独立光晕共用时长与平滑淡出。 */
public final class RVP_TransientLightTimeline {
    /** 倍率0按0.05计算，避免无限寿命；32刻爆炸最长640刻，即正常32秒。 */
    public static final float MIN_DECAY_SCALE = 0.05F;
    /** 最后20%寿命平滑归零，避免小于1的幂指数在末帧留下明显亮度断崖。 */
    private static final float SMOOTH_TAIL_FRACTION = 0.2F;

    private RVP_TransientLightTimeline() { }

    /** 衰减变慢则整盏光源变长，变快则变短；最短一游戏刻。 */
    public static float durationTicks(int baseTicks, float decayScale) {
        // 调用统一倍率修正，0或极小值不会制造无穷大的寿命。
        return Math.max(1F, baseTicks / effectiveScale(decayScale));
    }

    /** 根据原始出生年龄采样，照亮与光晕都在同一终点连续归零，不重置时钟。 */
    public static float fade(double ageTicks, int baseTicks, float baseDecay, float decayScale) {
        // 调用同一寿命规则，渲染采样、迟包判断、逻辑清理和效果包装器共享结束边界。
        float duration = durationTicks(baseTicks, decayScale);
        if (ageTicks >= duration) { return 0; }
        float remaining = (float) Math.max(0, Math.min(1, 1 - Math.max(0, ageTicks) / duration));
        float tail = Math.min(1, remaining / SMOOTH_TAIL_FRACTION);
        tail = tail * tail * (3 - 2 * tail);
        // 调用倍率修正；末段平滑曲线让终点亮度及一阶变化率连续归零。
        return (float) Math.pow(remaining, Math.max(0, baseDecay) * effectiveScale(decayScale)) * tail;
    }

    /** 命令入口拒绝非有限值；其他调用者传坏值时回退正常速度。 */
    private static float effectiveScale(float scale) {
        return Float.isFinite(scale) ? Math.max(MIN_DECAY_SCALE, scale) : 1F;
    }
}
