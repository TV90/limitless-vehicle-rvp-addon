package org.ywzj.rvp.client.visual.cookoff;

import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.item.AmmoItem;
import static org.junit.jupiter.api.Assertions.*;

/** 防止殉燃炮口喷火被小口径弹体或非火炮弹药错误放行。 */
class RVP_WreckCookoffWeaponPolicyTest {
    @Test
    void onlyAtLeast45MillimeterCannonsHaveMuzzleFire() {
        // 调用生产规则：弹药枚举不能绕过“仅 45 mm 以上”的口径阈值。
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.AUTO_CANNON, 19.05));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.ARTILLERY, 24.765));
        assertTrue(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.AUTO_CANNON, 45.0));
        assertTrue(RVP_WreckCookoffWeaponPolicy.accepts(true, AmmoItem.AmmoType.ARTILLERY, 125.0));
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
        assertTrue(RVP_WreckCookoffWeaponPolicy.accepts(true, null, 45));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, null, 44.99));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(false, null, 120));
        assertFalse(RVP_WreckCookoffWeaponPolicy.accepts(true, null, Double.POSITIVE_INFINITY));
    }
}
