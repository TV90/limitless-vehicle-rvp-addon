package org.ywzj.rvp.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 近处烟出生颜色与瘫痪引擎纵向收缩曲线的纯逻辑测试。 */
class RVP_NearSmokeParticleTest {

    /** 瘫痪引擎出生渲染帧必须是橙色，下一 Tick 结束橙色阶段。 */
    @Test
    void engineDisabledSmokeKeepsOneHotBirthTick() {
        assertTrue(RVP_NearSmokeParticle.isEngineHotPhase(0));
        assertFalse(RVP_NearSmokeParticle.isEngineHotPhase(1));
        assertFalse(RVP_NearSmokeParticle.isEngineHotPhase(2));
    }

    /** 橙色出生帧保持完整高度，之后底部锚定并收缩到预设高度。 */
    @Test
    void engineDisabledSmokeShrinksDownAfterHotFrame() {
        assertEquals(1.0f, RVP_NearSmokeParticle.resolveEngineHeightScale(0), 1.0E-6f);
        assertEquals(RVP_NearSmokeParticle.ENGINE_POST_HOT_HEIGHT_SCALE,
                RVP_NearSmokeParticle.resolveEngineHeightScale(1), 1.0E-6f);
    }

    /** 击毁载具近处烟的统一尺寸公式必须保持原有计算和上下限。 */
    @Test
    void wreckStyleNearSmokeKeepsUnifiedSizeRule() {
        assertEquals(0.66f,
                RVP_NearSmokeParticle.resolveWreckStyleSize(2.0, 0.5), 1.0E-6f);
        assertEquals(0.45f,
                RVP_NearSmokeParticle.resolveWreckStyleSize(0.5, 0.0), 1.0E-6f);
        assertEquals(1.65f,
                RVP_NearSmokeParticle.resolveWreckStyleSize(10.0, 1.0), 1.0E-6f);
    }
}
