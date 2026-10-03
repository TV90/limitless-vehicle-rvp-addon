package org.ywzj.rvp.client.visual;

import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 水下爆炸水幕的客户端运行时调参状态。
 *
 * <p>这些值只影响本地客户端后续生成的水幕粒子，不写入武器 JSON，也不改变服务端爆炸伤害或半径。
 * 调试命令通过本类修改，便于在游戏内快速比较水幕节奏。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_UnderwaterExplosionTuning {

    /** 水幕基础寿命倍率默认值；1.0 表示保留当前随机寿命分布。 */
    public static final float DEFAULT_LIFETIME_SCALE = 1.0F;
    /** 水幕回落到水面后的额外保留时长默认值，单位 tick。 */
    public static final int DEFAULT_RETURN_TAIL_TICKS = 4;
    /** 普通随机寿命的软上限默认值，单位 tick；物理所需回落寿命可以超过它。 */
    public static final int DEFAULT_MAX_LIFETIME_TICKS = 200;
    /** 水幕下降阶段的竖直速度阻尼默认值；0.96 表示每 tick 保留 96% 的既有下降速度。 */
    public static final float DEFAULT_DOWNWARD_DAMPING = 0.96F;
    /** 水幕水平向外扩散速度倍率默认值；相对旧公式缩小到 35%。 */
    public static final float DEFAULT_OUTWARD_SPREAD_SCALE = 0.35F;
    /** 每枚水幕粒子的基础随机尺寸倍率下限默认值。 */
    public static final float DEFAULT_SIZE_MULTIPLIER_MIN = 8.0F;
    /** 每枚水幕粒子的基础随机尺寸倍率上限默认值。 */
    public static final float DEFAULT_SIZE_MULTIPLIER_MAX = 12.0F;
    /** 水幕外围尺寸阻尼默认值；到水平半径边缘时保留基础随机尺寸的 35%。 */
    public static final float DEFAULT_OUTER_SIZE_DAMPING = 0.35F;
    /** 水幕成长到成熟状态所需时长默认值，单位 tick。 */
    public static final int DEFAULT_MATURE_TICKS = 8;
    /** 水幕成熟后是否保持成熟帧默认值。 */
    public static final boolean DEFAULT_HOLD_MATURE_FRAME = false;
    /** 水幕冲起初速度总倍率默认值。 */
    public static final float DEFAULT_UPWARD_SPEED_SCALE = 1.0F;
    /** 水幕中心区域冲起高度轮廓倍率默认值。 */
    public static final float DEFAULT_UPWARD_HEIGHT_SCALE = 1.0F;
    /** 水幕随机抬升速度贡献倍率默认值。 */
    public static final float DEFAULT_UPWARD_RANDOM_SCALE = 1.2F;

    /** 水幕基础寿命倍率的最小可调值。 */
    public static final float MIN_LIFETIME_SCALE = 0.1F;
    /** 水幕基础寿命倍率的最大可调值。 */
    public static final float MAX_LIFETIME_SCALE = 5.0F;
    /** 回落后额外保留时长的最大可调值，单位 tick。 */
    public static final int MAX_RETURN_TAIL_TICKS = 40;
    /** 普通随机寿命软上限的最小可调值，单位 tick。 */
    public static final int MIN_MAX_LIFETIME_TICKS = 1;
    /** 普通随机寿命软上限的最大可调值，单位 tick。 */
    public static final int MAX_MAX_LIFETIME_TICKS = 600;
    /** 下降阻尼的最小可调值；保持为正，避免调参后粒子永远停在空中。 */
    public static final float MIN_DOWNWARD_DAMPING = 0.05F;
    /** 下降阻尼的最大可调值。 */
    public static final float MAX_DOWNWARD_DAMPING = 1.0F;
    /** 水平向外扩散速度倍率的最小可调值。 */
    public static final float MIN_OUTWARD_SPREAD_SCALE = 0.0F;
    /** 水平向外扩散速度倍率的最大可调值。 */
    public static final float MAX_OUTWARD_SPREAD_SCALE = 2.0F;
    /** 水幕基础尺寸倍率的最小可调值。 */
    public static final float MIN_SIZE_MULTIPLIER = 1.0F;
    /** 水幕基础尺寸倍率的最大可调值。 */
    public static final float MAX_SIZE_MULTIPLIER = 20.0F;
    /** 水幕外围尺寸阻尼的最小可调值。 */
    public static final float MIN_OUTER_SIZE_DAMPING = 0.0F;
    /** 水幕外围尺寸阻尼的最大可调值。 */
    public static final float MAX_OUTER_SIZE_DAMPING = 1.0F;
    /** 水幕成长时长的最小可调值，单位 tick。 */
    public static final int MIN_MATURE_TICKS = 1;
    /** 水幕成长时长的最大可调值，单位 tick。 */
    public static final int MAX_MATURE_TICKS = 120;
    /** 水幕冲起初速度总倍率的最小可调值。 */
    public static final float MIN_UPWARD_SPEED_SCALE = 0.0F;
    /** 水幕冲起初速度总倍率的最大可调值。 */
    public static final float MAX_UPWARD_SPEED_SCALE = 4.0F;
    /** 水幕中心冲起高度轮廓倍率的最小可调值。 */
    public static final float MIN_UPWARD_HEIGHT_SCALE = 0.0F;
    /** 水幕中心冲起高度轮廓倍率的最大可调值。 */
    public static final float MAX_UPWARD_HEIGHT_SCALE = 4.0F;
    /** 水幕随机抬升速度贡献倍率的最小可调值。 */
    public static final float MIN_UPWARD_RANDOM_SCALE = 0.0F;
    /** 水幕随机抬升速度贡献倍率的最大可调值。 */
    public static final float MAX_UPWARD_RANDOM_SCALE = 4.0F;

    /** 当前水幕基础寿命倍率。 */
    private static volatile float lifetimeScale = DEFAULT_LIFETIME_SCALE;
    /** 当前水幕回落后的额外保留时长，单位 tick。 */
    private static volatile int returnTailTicks = DEFAULT_RETURN_TAIL_TICKS;
    /** 当前普通随机寿命的软上限，单位 tick。 */
    private static volatile int maxLifetimeTicks = DEFAULT_MAX_LIFETIME_TICKS;
    /** 当前下降阶段的竖直速度阻尼。 */
    private static volatile float downwardDamping = DEFAULT_DOWNWARD_DAMPING;
    /** 当前水幕水平向外扩散速度倍率。 */
    private static volatile float outwardSpreadScale = DEFAULT_OUTWARD_SPREAD_SCALE;
    /** 当前水幕基础随机尺寸倍率下限。 */
    private static volatile float sizeMultiplierMin = DEFAULT_SIZE_MULTIPLIER_MIN;
    /** 当前水幕基础随机尺寸倍率上限。 */
    private static volatile float sizeMultiplierMax = DEFAULT_SIZE_MULTIPLIER_MAX;
    /** 当前水幕外围尺寸阻尼保留比例。 */
    private static volatile float outerSizeDamping = DEFAULT_OUTER_SIZE_DAMPING;
    /** 当前水幕成长到成熟状态所需时长，单位 tick。 */
    private static volatile int matureTicks = DEFAULT_MATURE_TICKS;
    /** 当前水幕是否在成熟后保持成熟帧。 */
    private static volatile boolean holdMatureFrame = DEFAULT_HOLD_MATURE_FRAME;
    /** 当前水幕冲起初速度总倍率。 */
    private static volatile float upwardSpeedScale = DEFAULT_UPWARD_SPEED_SCALE;
    /** 当前水幕中心冲起高度轮廓倍率。 */
    private static volatile float upwardHeightScale = DEFAULT_UPWARD_HEIGHT_SCALE;
    /** 当前水幕随机抬升速度贡献倍率。 */
    private static volatile float upwardRandomScale = DEFAULT_UPWARD_RANDOM_SCALE;

    private RVP_UnderwaterExplosionTuning() {
    }

    /** 返回当前水幕基础寿命倍率。 */
    public static float getLifetimeScale() {
        return lifetimeScale;
    }

    /** 设置水幕基础寿命倍率，并限制到调试命令允许范围。 */
    public static void setLifetimeScale(float value) {
        lifetimeScale = Mth.clamp(value, MIN_LIFETIME_SCALE, MAX_LIFETIME_SCALE);
    }

    /** 返回当前回落后的额外保留时长，单位 tick。 */
    public static int getReturnTailTicks() {
        return returnTailTicks;
    }

    /** 设置回落后的额外保留时长，并限制到调试命令允许范围。 */
    public static void setReturnTailTicks(int value) {
        returnTailTicks = Mth.clamp(value, 0, MAX_RETURN_TAIL_TICKS);
    }

    /** 返回普通随机寿命软上限，单位 tick。 */
    public static int getMaxLifetimeTicks() {
        return maxLifetimeTicks;
    }

    /** 设置普通随机寿命软上限，并限制到调试命令允许范围。 */
    public static void setMaxLifetimeTicks(int value) {
        maxLifetimeTicks = Mth.clamp(value, MIN_MAX_LIFETIME_TICKS, MAX_MAX_LIFETIME_TICKS);
    }

    /** 返回下降阶段的竖直速度阻尼。 */
    public static float getDownwardDamping() {
        return downwardDamping;
    }

    /** 设置下降阶段的竖直速度阻尼，并限制到调试命令允许范围。 */
    public static void setDownwardDamping(float value) {
        downwardDamping = Mth.clamp(value, MIN_DOWNWARD_DAMPING, MAX_DOWNWARD_DAMPING);
    }

    /** 返回当前水幕水平向外扩散速度倍率。 */
    public static float getOutwardSpreadScale() {
        return outwardSpreadScale;
    }

    /** 设置水幕水平向外扩散速度倍率，并限制到调试命令允许范围。 */
    public static void setOutwardSpreadScale(float value) {
        outwardSpreadScale = Mth.clamp(value, MIN_OUTWARD_SPREAD_SCALE, MAX_OUTWARD_SPREAD_SCALE);
    }

    /** 返回当前水幕基础随机尺寸倍率下限。 */
    public static float getSizeMultiplierMin() {
        return sizeMultiplierMin;
    }

    /** 设置水幕基础随机尺寸倍率下限，并保持上下限顺序有效。 */
    public static void setSizeMultiplierMin(float value) {
        sizeMultiplierMin = Mth.clamp(value, MIN_SIZE_MULTIPLIER, MAX_SIZE_MULTIPLIER);
        if (sizeMultiplierMin > sizeMultiplierMax) {
            sizeMultiplierMax = sizeMultiplierMin;
        }
    }

    /** 返回当前水幕基础随机尺寸倍率上限。 */
    public static float getSizeMultiplierMax() {
        return sizeMultiplierMax;
    }

    /** 设置水幕基础随机尺寸倍率上限，并保持上下限顺序有效。 */
    public static void setSizeMultiplierMax(float value) {
        sizeMultiplierMax = Mth.clamp(value, MIN_SIZE_MULTIPLIER, MAX_SIZE_MULTIPLIER);
        if (sizeMultiplierMax < sizeMultiplierMin) {
            sizeMultiplierMin = sizeMultiplierMax;
        }
    }

    /** 返回水幕外围尺寸阻尼保留比例。 */
    public static float getOuterSizeDamping() {
        return outerSizeDamping;
    }

    /** 设置水幕外围尺寸阻尼保留比例，并限制到调试命令允许范围。 */
    public static void setOuterSizeDamping(float value) {
        outerSizeDamping = Mth.clamp(value, MIN_OUTER_SIZE_DAMPING, MAX_OUTER_SIZE_DAMPING);
    }

    /** 返回水幕成长到成熟状态所需时长，单位 tick。 */
    public static int getMatureTicks() {
        return matureTicks;
    }

    /** 设置水幕成长到成熟状态所需时长，并限制到调试命令允许范围。 */
    public static void setMatureTicks(int value) {
        matureTicks = Mth.clamp(value, MIN_MATURE_TICKS, MAX_MATURE_TICKS);
    }

    /** 返回水幕是否在成熟后保持成熟帧。 */
    public static boolean isHoldMatureFrame() {
        return holdMatureFrame;
    }

    /** 设置水幕是否在成熟后保持成熟帧。 */
    public static void setHoldMatureFrame(boolean value) {
        holdMatureFrame = value;
    }

    /** 返回水幕冲起初速度总倍率。 */
    public static float getUpwardSpeedScale() {
        return upwardSpeedScale;
    }

    /** 设置水幕冲起初速度总倍率，并限制到调试命令允许范围。 */
    public static void setUpwardSpeedScale(float value) {
        upwardSpeedScale = Mth.clamp(value, MIN_UPWARD_SPEED_SCALE, MAX_UPWARD_SPEED_SCALE);
    }

    /** 返回水幕中心冲起高度轮廓倍率。 */
    public static float getUpwardHeightScale() {
        return upwardHeightScale;
    }

    /** 设置水幕中心冲起高度轮廓倍率，并限制到调试命令允许范围。 */
    public static void setUpwardHeightScale(float value) {
        upwardHeightScale = Mth.clamp(value, MIN_UPWARD_HEIGHT_SCALE, MAX_UPWARD_HEIGHT_SCALE);
    }

    /** 返回水幕随机抬升速度贡献倍率。 */
    public static float getUpwardRandomScale() {
        return upwardRandomScale;
    }

    /** 设置水幕随机抬升速度贡献倍率，并限制到调试命令允许范围。 */
    public static void setUpwardRandomScale(float value) {
        upwardRandomScale = Mth.clamp(value, MIN_UPWARD_RANDOM_SCALE, MAX_UPWARD_RANDOM_SCALE);
    }

    /** 恢复水幕调参的默认值。 */
    public static void reset() {
        lifetimeScale = DEFAULT_LIFETIME_SCALE;
        returnTailTicks = DEFAULT_RETURN_TAIL_TICKS;
        maxLifetimeTicks = DEFAULT_MAX_LIFETIME_TICKS;
        downwardDamping = DEFAULT_DOWNWARD_DAMPING;
        outwardSpreadScale = DEFAULT_OUTWARD_SPREAD_SCALE;
        sizeMultiplierMin = DEFAULT_SIZE_MULTIPLIER_MIN;
        sizeMultiplierMax = DEFAULT_SIZE_MULTIPLIER_MAX;
        outerSizeDamping = DEFAULT_OUTER_SIZE_DAMPING;
        matureTicks = DEFAULT_MATURE_TICKS;
        holdMatureFrame = DEFAULT_HOLD_MATURE_FRAME;
        upwardSpeedScale = DEFAULT_UPWARD_SPEED_SCALE;
        upwardHeightScale = DEFAULT_UPWARD_HEIGHT_SCALE;
        upwardRandomScale = DEFAULT_UPWARD_RANDOM_SCALE;
    }

    /** 返回适合客户端调试命令直接显示的当前参数摘要。 */
    public static String describe() {
        return "lifetimeScale=" + lifetimeScale
                + ", returnTail=" + returnTailTicks
                + ", maxLifetime=" + maxLifetimeTicks
                + ", downwardDamping=" + downwardDamping
                + ", outwardSpread=" + outwardSpreadScale
                + ", sizeRange=" + sizeMultiplierMin + ".." + sizeMultiplierMax
                + ", outerSizeDamping=" + outerSizeDamping
                + ", matureTicks=" + matureTicks
                + ", holdMatureFrame=" + holdMatureFrame
                + ", upwardSpeed=" + upwardSpeedScale
                + ", upwardHeight=" + upwardHeightScale
                + ", upwardRandom=" + upwardRandomScale;
    }
}
