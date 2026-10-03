package org.ywzj.rvp.client.visual;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 水下爆炸水幕的回落寿命与客户端调参纯逻辑测试。 */
class RVP_UnderwaterExplosionPhysicsTest {

    @AfterEach
    void resetTuning() {
        RVP_UnderwaterExplosionTuning.reset();
    }

    @Test
    void returnTicksCoverRiseAndFallWhenParticleStartsBelowSurface() {
        int returnTicks = RVP_UnderwaterExplosionPhysics.resolveReturnTicks(
                99.5D, 100.0D, 1.2D, RVP_UnderwaterExplosionTuning.DEFAULT_DOWNWARD_DAMPING);

        assertTrue(returnTicks > 1, "水幕应先越过水面再回落");
        assertTrue(returnTicks < 200, "默认速度与阻尼不应产生异常长回落时间");
    }

    @Test
    void lifetimeGuaranteeWinsOverSoftCap() {
        RVP_UnderwaterExplosionTuning.setMaxLifetimeTicks(1);
        RVP_UnderwaterExplosionTuning.setReturnTailTicks(4);

        int lifetime = RVP_UnderwaterExplosionPhysics.resolveLifetime(
                RandomSource.create(114514L), 30);

        assertTrue(lifetime >= 35, "回落寿命不能被普通寿命软上限截断");
    }

    @Test
    void dampingRemainsBoundedByRuntimeTuning() {
        RVP_UnderwaterExplosionTuning.setDownwardDamping(0.5F);

        int returnTicks = RVP_UnderwaterExplosionPhysics.resolveReturnTicks(
                99.0D, 100.0D, 1.0D, RVP_UnderwaterExplosionTuning.getDownwardDamping());

        assertTrue(returnTicks > 1, "下降阻尼调参不应让水幕立即结束");
    }

    @Test
    void sizeMultiplierUsesConfiguredRangeAtCenterAndDampsOutward() {
        float centerMinimum = RVP_UnderwaterExplosionPhysics.resolveSizeMultiplier(0.0F, 0.0D, 6.0F);
        float centerMaximum = RVP_UnderwaterExplosionPhysics.resolveSizeMultiplier(1.0F, 0.0D, 6.0F);
        float outerMinimum = RVP_UnderwaterExplosionPhysics.resolveSizeMultiplier(0.0F, 6.0D, 6.0F);
        float outerMaximum = RVP_UnderwaterExplosionPhysics.resolveSizeMultiplier(1.0F, 6.0D, 6.0F);

        assertTrue(centerMinimum >= RVP_UnderwaterExplosionTuning.DEFAULT_SIZE_MULTIPLIER_MIN
                        && centerMinimum <= RVP_UnderwaterExplosionTuning.DEFAULT_SIZE_MULTIPLIER_MIN + 0.01F,
                "爆心最小随机尺寸应符合当前默认下限");
        assertTrue(centerMaximum >= RVP_UnderwaterExplosionTuning.DEFAULT_SIZE_MULTIPLIER_MAX - 0.01F
                        && centerMaximum <= RVP_UnderwaterExplosionTuning.DEFAULT_SIZE_MULTIPLIER_MAX,
                "爆心最大随机尺寸应符合当前默认上限");
        assertTrue(outerMinimum < centerMinimum && outerMaximum < centerMaximum,
                "外围尺寸应经过阻尼后小于爆心尺寸");
    }
}
