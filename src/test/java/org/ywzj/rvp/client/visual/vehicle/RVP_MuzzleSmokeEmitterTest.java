package org.ywzj.rvp.client.visual.vehicle;

import org.ywzj.vehicle.item.AmmoItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 炮口烟口径尺寸映射测试，确保口径越大烟团越大且始终受上下限保护。 */
class RVP_MuzzleSmokeEmitterTest {

    /** 7.62 mm 为标准基准尺寸。 */
    @Test
    void referenceCaliberUsesReferenceSize() {
        assertEquals(2.0f, RVP_MuzzleSmokeEmitter.resolveParticleSize(7.62f), 1.0E-6f);
    }

    /** 常见机炮与坦克炮口径必须产生单调增大的烟团。 */
    @Test
    void largerCaliberProducesLargerSmoke() {
        float machineGun = RVP_MuzzleSmokeEmitter.resolveParticleSize(7.62f);
        float autocannon = RVP_MuzzleSmokeEmitter.resolveParticleSize(30.0f);
        float cannon = RVP_MuzzleSmokeEmitter.resolveParticleSize(105.0f);
        assertTrue(machineGun < autocannon);
        assertTrue(autocannon < cannon);
    }

    /** 极大口径必须被上限钳制，避免异常配置造成粒子刷屏。 */
    @Test
    void extremeCaliberIsCapped() {
        assertEquals(5.0f,
                RVP_MuzzleSmokeEmitter.resolveParticleSize(Float.MAX_VALUE), 1.0E-6f);
    }

    /** 非法口径回退到 7.62 mm，保持可见且不传播 NaN。 */
    @Test
    void invalidCaliberFallsBackToReference() {
        assertEquals(2.0f,
                RVP_MuzzleSmokeEmitter.resolveParticleSize(Float.NaN), 1.0E-6f);
        assertEquals(2.0f,
                RVP_MuzzleSmokeEmitter.resolveParticleSize(0.0f), 1.0E-6f);
    }

    /** 45 mm 及以上火炮不生成炮口烟，44.99 mm 仍属于允许范围。 */
    @Test
    void largeCaliberDoesNotUseMuzzleSmoke() {
        assertTrue(RVP_MuzzleSmokeEmitter.supportsMuzzleSmoke(44.99f));
        assertFalse(RVP_MuzzleSmokeEmitter.supportsMuzzleSmoke(45.0f));
        assertFalse(RVP_MuzzleSmokeEmitter.supportsMuzzleSmoke(105.0f));
    }

    /** T-90M 主炮弹体虽为 24.765 mm，火炮弹药类别仍必须阻止炮口烟。 */
    @Test
    void artilleryAmmoDoesNotUseMuzzleSmoke() {
        assertTrue(RVP_MuzzleSmokeEmitter.isHeavyArtilleryAmmoType(AmmoItem.AmmoType.ARTILLERY));
        assertFalse(RVP_MuzzleSmokeEmitter.isHeavyArtilleryAmmoType(AmmoItem.AmmoType.AUTO_CANNON));
    }
}
