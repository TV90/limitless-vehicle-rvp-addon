package org.ywzj.rvp.light;

/** RVP 程序喷焰代码参数；几何沿用 flame_scale，广域范围沿用既有口径，不新增亮度/光晕 JSON。 */
public final class RVP_RocketPlumeParameters {
    /** 新的三维程序喷焰总开关。 */
    public static final boolean ENABLED = true;
    /** 暂停本体模型/动画/贴图尾焰，保留旧实现便于后续对照。 */
    public static final boolean ORIGINAL_MODEL_ENABLED = false;
    /** 暂停旧圆形喷口高亮，保留原 Shader 与参数，避免与新喷焰重复叠加。 */
    public static final boolean ROUND_SOURCE_ENABLED = false;
    /** 沿用旧尾焰未配置 flame_scale 时的缩放。 */
    public static final float DEFAULT_SCALE = 0.3F;
    /** 缩放为 1 时的喷流长度，单位格；默认缩放下长 3 格。 */
    public static final float BASE_LENGTH = 10F;
    /** 缩放为 1 时的喷流最大半径，单位格；直径保持接近原模型的 3.09 格。 */
    public static final float BASE_RADIUS = 1.5F;
    /** 点火后喷流展开并显现的时间，单位 tick；3 tick 为 0.15 秒，按游戏时钟推进。 */
    public static final float IGNITION_TRANSITION_TICKS = 3F;
    /** 当前燃烧窗口结束前收束并淡出的时间，单位 tick；6 tick 为 0.3 秒，不延长实际燃烧期。 */
    public static final float BURNOUT_TRANSITION_TICKS = 6F;
    /** 仅供光晕提取的软压缩峰值；核心显示仍使用 60000，防止有限模糊核被高亮烧成方形硬边。 */
    public static final float BLOOM_INPUT_PEAK = 12F;
    /** 喷焰使用连续的标准模糊采样步长，避免细长高亮边缘被稀疏采样分成条纹。 */
    public static final float BLOOM_BLUR_STEP = 1F;
    /** 独立喷焰可见数量，不能套用环境照明的八灯限制。 */
    public static final int MAX_VISIBLE = 128;
    /** 待筛选喷焰的内存上限；优先淘汰最旧输入。 */
    public static final int MAX_TRACKED = 256;

    private RVP_RocketPlumeParameters() { }

    /** 缺失、非有限或非正缩放沿用旧默认；不随速度、口径或发光强度改变几何尺寸。 */
    public static float scale(Float configured) {
        return configured != null && Float.isFinite(configured) && configured > 0
                && Float.isFinite(configured * BASE_LENGTH) ? configured : DEFAULT_SCALE;
    }

    /** 点火与燃尽两端均平滑到零；短燃烧期允许两段重叠，首尾不会突然跳亮。 */
    public static float transition(double elapsedTicks, double remainingTicks) {
        // 调用两端斜率为零的平滑曲线，让逐帧插值的展开和收束没有折点。
        return smooth(elapsedTicks / IGNITION_TRANSITION_TICKS)
                * smooth(remainingTicks / BURNOUT_TRANSITION_TICKS);
    }

    /** 将时间比例压到 0～1 后做平滑插值；未知燃尽时间的正无穷按持续燃烧处理。 */
    private static float smooth(double progress) {
        double value = Double.isNaN(progress) ? 0 : Math.max(0, Math.min(1, progress));
        return (float) (value * value * (3 - 2 * value));
    }
}
