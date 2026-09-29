package org.ywzj.rvp.client.visual.cookoff;

import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.item.AmmoItem;
import static org.junit.jupiter.api.Assertions.*;

/** 防止真实机炮因较小曳光口径被排除，或导弹因粗曳光被误识别。 */
class RVP_WreckCookoffWeaponPolicyTest {
    @Test
    void autocannonWithNineteenMillimeterTracerStillHasMuzzleFire() {
        // 调用生产规则，复现当前包内 25/30mm 机炮使用 19.05 可视口径的情况。
        assertTrue(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.AUTO_CANNON, 19.05));
        assertTrue(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.ARTILLERY, 7.62));
    }

    @Test
    void missileAndMachineGunNeverBecomeCannonBecauseOfLargeTracer() {
        // 调用生产规则，武器行为与装填枚举优先于渲染口径。
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(false, AmmoItem.AmmoType.ARTILLERY, 120));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.MACHINE_GUN, 120));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.MISSILE, 120));
    }

    @Test
    void unknownAmmoUsesFiniteCaliberFallbackOnlyForBallisticWeapon() {
        // 调用生产规则，第三方弹药缺少类型时才允许有限口径回退。
        assertTrue(RVP_WreckCookoffWeaponPolicy.accepts(true, null, 30));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, null, 7.62));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(false, null, 120));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, null, Double.POSITIVE_INFINITY));
    }
}
