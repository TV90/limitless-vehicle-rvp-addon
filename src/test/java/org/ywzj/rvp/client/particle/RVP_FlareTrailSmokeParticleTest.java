package org.ywzj.rvp.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证热诱弹拖尾烟的贴图复用与膨胀/淡出曲线；不启动客户端世界或粒子引擎。 */
class RVP_FlareTrailSmokeParticleTest {

    @Test
    void trailSmokeReusesTheWreckCookoffTextureSet() {
        // 调用本项目殉燃贴图集边界入口：拖尾烟必须与车顶火柱/炮口烟使用同一目录的同一批烟雾贴图。
        assertEquals(RVP_WreckCookoffParticleTextures.count(), RVP_FlareTrailSmokeParticle.textureCount());
        assertTrue(RVP_FlareTrailSmokeParticle.textureCount() > 0);
    }

    @Test
    void fadeKeepsFullAlphaThenFallsToZero() {
        // 调用透明度曲线：前 55% 寿命保持满值，避免刚出生就发虚；寿命末必须完全淡出。
        assertEquals(1.0F, RVP_FlareTrailSmokeParticle.fadeFactor(0.0F), 1.0E-6F);
        assertEquals(1.0F, RVP_FlareTrailSmokeParticle.fadeFactor(0.55F), 1.0E-6F);
        assertEquals(0.0F, RVP_FlareTrailSmokeParticle.fadeFactor(1.0F), 1.0E-6F);
        assertTrue(RVP_FlareTrailSmokeParticle.fadeFactor(0.8F)
                < RVP_FlareTrailSmokeParticle.fadeFactor(0.6F));
        // 越界进度钳制到两端，渲染时不会出现负透明度。
        assertEquals(1.0F, RVP_FlareTrailSmokeParticle.fadeFactor(-1.0F), 1.0E-6F);
        assertEquals(0.0F, RVP_FlareTrailSmokeParticle.fadeFactor(2.0F), 1.0E-6F);
    }

    @Test
    void sizeGrowsOverTheWholeLifetime() {
        // 调用尺寸曲线：烟团从 0.80 倍出生尺寸膨胀到寿命末的 1.85 倍，越老越淡也越大。
        assertEquals(0.80F, RVP_FlareTrailSmokeParticle.growthFactor(0.0F), 1.0E-6F);
        assertEquals(1.85F, RVP_FlareTrailSmokeParticle.growthFactor(1.0F), 1.0E-6F);
        assertTrue(RVP_FlareTrailSmokeParticle.growthFactor(0.5F)
                > RVP_FlareTrailSmokeParticle.growthFactor(0.25F));
    }
}
