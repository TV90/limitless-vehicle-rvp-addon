package org.ywzj.rvp.client.visual;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Values;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeLogic;

import static org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter.*;

/** 击毁烟的运动、消散与生成数值计算；正式粒子和无伤害预览共用。 */
public final class RVP_WreckSmokeLogic {
    private RVP_WreckSmokeLogic() { }

    /** 原有逐团随机灰度加上可调均值和随机差异；默认严格保留原始灰度。 */
    public static float grey(float original, float originalMean, double mean, double variation) {
        return Mth.clamp(original + ((float) mean - originalMean)
                + (original - originalMean) * ((float) variation - 1), 0, 1);
    }

    /** 原有随机浓度的均值不变，单独调整逐团差异和总体倍率。 */
    public static float alpha(float original, float originalMean, double variation, double scale) {
        return Mth.clamp((original + (original - originalMean) * ((float) variation - 1)) * (float) scale, 0, 1);
    }

    /** 原有短烟从首次更新起使用的线性膨胀曲线。 */
    public static float nearSizeMultiplier(Values settings, float progress) {
        return (float) settings.value(NEAR_INITIAL_SIZE_FACTOR) + progress * (float) settings.value(NEAR_GROWTH_AMOUNT);
    }

    /** 同时处理出生淡入、寿命淡出和可选膨胀稀释；零时长和末尾起淡均安全。 */
    public static float alphaMultiplier(Values settings, boolean near, int age, int lifetime, float sizeMultiplier) {
        float progress = Mth.clamp((float) age / Math.max(1, lifetime), 0, 1);
        if (progress >= 1) { return 0; }
        int fadeIn = settings.integer(near ? NEAR_FADE_IN_TICKS : LONG_FADE_IN_TICKS);
        float fadeStart = (float) settings.value(near ? NEAR_FADE_START : FADE_START);
        float alpha = fadeIn == 0 ? 1 : Mth.clamp((float) age / fadeIn, 0, 1);
        if (fadeStart < 1 && progress > fadeStart) { alpha *= (1 - progress) / (1 - fadeStart); }
        double power = settings.value(near ? NEAR_EXPANSION_FADE : LONG_EXPANSION_FADE);
        float startSize = near ? (float) settings.value(NEAR_INITIAL_SIZE_FACTOR) : 1;
        // 目的：缩小阶段不额外增浓，只有比膨胀起点更大时才按比例变薄。
        return alpha * (float) Math.pow(Math.min(1, startSize / Math.max(0.001f, sizeMultiplier)), power);
    }

    /** 长烟现有三帧成团过程；0刻立即使用完整帧，避免除零。 */
    public static int longFrame(int age, int formationTicks) {
        return age < formationTicks ? 2 - Math.min(2, age * 3 / formationTicks) : 0;
    }

    /** 短烟只播放已有16帧；快放到末帧后保持，不循环跳回首帧。 */
    public static int nearFrame(float progress, double rate) {
        return Mth.clamp((int) (progress * 16 * rate), 0, 15);
    }

    /** 小数烟量按概率补足；倍率为1时不额外抽随机数，保留原有生成序列。 */
    public static int nearCount(int baseCount, double scale, RandomSource random) {
        double expected = baseCount * scale;
        int count = (int) Math.floor(expected);
        double fraction = expected - count;
        return count + (fraction > 0 && random.nextDouble() < fraction ? 1 : 0);
    }

    /** 一刻的随风、上浮和翻卷；默认等价于原来长烟、短烟各自的运动公式。 */
    public static Vec3 velocityStep(Values settings, boolean near, Vec3 velocity, Vec3 position,
                                    double worldTime, int age, double phase, double rise) {
        double windGain = near ? 1 : 1 + Math.max(0, rise) * settings.value(WIND_GAIN);
        double windX = settings.value(near ? NEAR_EAST_WIND : EAST_WIND) * windGain;
        double windZ = settings.value(near ? NEAR_SOUTH_WIND : LONG_SOUTH_WIND) * windGain;
        double drag = settings.value(near ? NEAR_HORIZONTAL_DRAG : LONG_HORIZONTAL_DRAG);
        double updraft = settings.value(near ? NEAR_UPDRAFT : UPDRAFT);
        double response = settings.value(near ? NEAR_UPDRAFT_RESPONSE : LONG_UPDRAFT_RESPONSE);
        double sway = settings.value(near ? NEAR_SWAY : SWAY);
        double wave = age * (Math.PI * 2 / settings.value(near ? NEAR_SWAY_PERIOD : LONG_SWAY_PERIOD)) + phase;
        double vertical = near ? velocity.y * (1 - response) + updraft * response
                : updraft + (velocity.y - updraft) * (1 - response);
        Vec3 result = new Vec3(windX + (velocity.x - windX) * (1 - drag) + Math.sin(wave) * sway,
                vertical, windZ + (velocity.z - windZ) * (1 - drag) + Math.cos(wave) * sway);
        double strength = settings.value(near ? NEAR_CURL_STRENGTH : LONG_CURL_STRENGTH);
        if (strength == 0) { return result; }
        // 复用本项目既有连续气流函数：邻近烟团共享同一空间和时间，避免逐团随机抖动。
        return result.add(RVP_DelayedMuzzleSmokeLogic.curl(position,
                worldTime * 0.035 * settings.value(near ? NEAR_CURL_RATE : LONG_CURL_RATE),
                settings.value(near ? NEAR_CURL_SCALE : LONG_CURL_SCALE)).scale(strength));
    }
}
