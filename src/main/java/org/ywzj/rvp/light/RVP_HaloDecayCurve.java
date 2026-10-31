package org.ywzj.rvp.light;

/** 所有有寿命的 RVP 光晕共用的亮度曲线；只返回亮度比例，不改变照亮、颜色或寿命。 */
public enum RVP_HaloDecayCurve {
    /** 保留光源原有的淡出方式。 */
    ORIGINAL("原有衰减"),
    /** 从出生时的全亮匀速降到寿命终点的零亮度。 */
    LINEAR("线性衰减"),
    /** 先快速降亮，再保持低亮，最后平滑消失。 */
    FLASH_HOLD("快降后保持"),
    /** 爆炸先保持峰值，再从峰值平滑降到零；保持刻数由实际年龄入口处理。 */
    PEAK_HOLD_FADE("高亮保持后淡出");

    /** 面板展示的中文曲线名称；枚举顺序对应命令中的 0、1、2、3。 */
    private final String title;

    RVP_HaloDecayCurve(String title) { this.title = title; }

    /** 返回可直接展示的中文名称。 */
    public String title() { return title; }

    /**
     * 采样光晕亮度，结果为 0～1；新曲线直接替代原包络，不能再乘原淡出而破坏低亮保持段。
     * @param progress 已经过的寿命比例，0 为出生、1 为结束
     * @param originalFade 原曲线的亮度比例，仅 ORIGINAL 使用
     * @param dropFraction 快速降亮所占的寿命比例，默认 0.1，限制为 0.001～0.5
     * @param lowBrightness 保持段相对初始亮度的比例，默认 0.15，范围 0～1
     * @param holdUntil 低亮保持到的寿命位置，默认 0.8，范围 0.5～0.99；之后平滑归零
     */
    public float fade(float progress, float originalFade, float dropFraction, float lowBrightness, float holdUntil) {
        if (!Float.isFinite(progress) || progress >= 1) { return 0; }
        float t = Math.max(0, progress);
        if (this == ORIGINAL) {
            return Float.isFinite(originalFade) ? Math.max(0, Math.min(1, originalFade)) : 0;
        }
        if (this == LINEAR) { return 1 - t; }
        if (this == PEAK_HOLD_FADE) {
            float remaining = 1 - t;
            // 保持段之后采用两端斜率均为零的三次平滑函数，避免开始淡出和归零时跳亮。
            return remaining * remaining * (3 - 2 * remaining);
        }
        // 调用同一边界修正，直接复用曲线的调用者也不能把非有限参数送入渲染输入。
        float drop = bounded(dropFraction, 0.001F, 0.5F, 0.1F);
        float low = bounded(lowBrightness, 0, 1, 0.15F);
        float hold = bounded(holdUntil, 0.5F, 0.99F, 0.8F);
        if (t < drop) {
            // 三次曲线在开头降得快，接近低亮时放缓，并以零斜率接入保持段。
            float remaining = 1 - t / drop;
            return low + (1 - low) * remaining * remaining * remaining;
        }
        if (t <= hold) { return low; }
        float remaining = (1 - t) / (1 - hold);
        // 平滑归零函数在保持段末端和寿命终点都没有亮度跳变。
        return low * remaining * remaining * (3 - 2 * remaining);
    }

    /**
     * 按实际游戏刻采样高亮保持曲线；保持3刻就是3刻，不随整盏光源寿命倍率一起伸缩。
     * @param ageTicks 从爆炸出生起经过的游戏刻，含部分刻
     * @param holdTicks 保持初始亮度的游戏刻数
     * @param fadeTicks 保持结束后平滑淡出的游戏刻数，必须大于0
     */
    public static float peakHoldFade(double ageTicks, float holdTicks, float fadeTicks) {
        if (!Double.isFinite(ageTicks) || !Float.isFinite(fadeTicks) || fadeTicks <= 0) { return 0; }
        float hold = Float.isFinite(holdTicks) ? Math.max(0, holdTicks) : 0;
        float progress = (float) ((Math.max(0, ageTicks) - hold) / fadeTicks);
        // 调用同一平滑函数；保持期间进度为负，采样入口将其限制为出生时的全亮。
        return PEAK_HOLD_FADE.fade(progress, 1, 0.1F, 0.15F, 0.8F);
    }

    /** 非有限参数回退默认值；快速段最长半个寿命，保持终点至少半个寿命，保证三段顺序。 */
    private static float bounded(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
