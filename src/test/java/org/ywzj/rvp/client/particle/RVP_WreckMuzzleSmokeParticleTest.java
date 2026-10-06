package org.ywzj.rvp.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证炮口殉燃灰烟的车顶贴图复用边界和向上漂移速度收敛规则。 */
class RVP_WreckMuzzleSmokeParticleTest {

    @Test
    void smokeUsesTheRoofFlameTextureSet() {
        // 调用本项目车顶喷燃贴图边界入口，炮口灰烟必须与车顶共用完整贴图集合。
        assertEquals(RVP_WreckFlameParticle.textureCount(), RVP_WreckMuzzleSmokeParticle.textureCount());
        assertTrue(RVP_WreckMuzzleSmokeParticle.textureCount() > 0);
        assertEquals(15, RVP_WreckMuzzleSmokeParticle.normalizeTextureIndex(-1));
        assertEquals(12, RVP_WreckMuzzleSmokeParticle.normalizeTextureIndex(12));
        assertEquals(13, RVP_WreckMuzzleSmokeParticle.normalizeTextureIndex(13));
    }

    @Test
    void smokeVerticalVelocityMovesTowardConfiguredUpdraft() {
        // 调用本项目上浮速度入口，当前速度必须向目标上浮速度收敛且保持向上趋势。
        double first = RVP_WreckMuzzleSmokeParticle.approachUpdraft(0.0D, 0.045D);
        double second = RVP_WreckMuzzleSmokeParticle.approachUpdraft(first, 0.045D);
        assertTrue(first > 0.0D && first < 0.045D);
        assertTrue(second > first && second < 0.045D);
        double decayed = RVP_WreckMuzzleSmokeParticle.approachUpdraft(0.2D, 0.0D);
        assertTrue(decayed > 0.0D && decayed < 0.2D);
    }
}
