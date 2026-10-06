package org.ywzj.rvp.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证殉燃静态贴图粒子的边界；不启动客户端世界或粒子引擎。 */
class RVP_WreckFlameParticleTest {

    @Test
    void staticTextureSetContainsAllImportedVariants() {
        // 13 张贴图均为独立静态变体，数量变化时应同步调整资源数组和本断言。
        assertEquals(16, RVP_WreckFlameParticle.textureCount());
    }

    @Test
    void textureIndexWrapsWithoutSelectingAnAnimationFrame() {
        // 调用静态索引规范化：负数与超范围输入只选择固定贴图变体，不按年龄计算帧。
        assertEquals(0, RVP_WreckFlameParticle.normalizeTextureIndex(0));
        assertEquals(15, RVP_WreckFlameParticle.normalizeTextureIndex(-1));
        assertEquals(13, RVP_WreckFlameParticle.normalizeTextureIndex(13));
        assertEquals(2, RVP_WreckFlameParticle.normalizeTextureIndex(18));
    }

    @Test
    void lifetimeNeverFallsBelowVisibleSprayMinimum() {
        // 调用寿命下限保护，确保短粒子至少能显示喷出与淡出过程。
        assertEquals(4, RVP_WreckFlameParticle.clampLifetime(0));
        assertEquals(4, RVP_WreckFlameParticle.clampLifetime(4));
        assertEquals(12, RVP_WreckFlameParticle.clampLifetime(12));
    }

    @Test
    void highSpeedRoofParticleWrapsWithoutLeavingPredictableHeight() {
        // 调用本项目轴向循环入口：速度再高也只能在目标高度范围内循环，不能把贴图甩出火柱。
        assertEquals(0.5D, RVP_WreckFlameParticle.wrapTravelDistance(6.5D, 6.0D), 1.0E-9D);
        assertEquals(0.2D, RVP_WreckFlameParticle.wrapTravelDistance(12.2D, 6.0D), 1.0E-9D);
        assertEquals(5.5D, RVP_WreckFlameParticle.wrapTravelDistance(-0.5D, 6.0D), 1.0E-9D);
    }
}
