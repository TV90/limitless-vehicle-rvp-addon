package org.ywzj.rvp.client.visual.cookoff;

import org.ywzj.vehicle.item.AmmoItem;

/** 火炮识别的纯规则：优先现有装填弹药类型，避免把曳光可视口径误当实际口径。 */
public final class RVP_WreckCookoffWeaponPolicy {
    private RVP_WreckCookoffWeaponPolicy() {}

    /** 弹道炮弹类才允许炮口喷燃；未知第三方弹药才以口径作保守回退。 */
    public static boolean accepts(boolean ballistic, AmmoItem.AmmoType ammoType, double caliber) {
        if (!ballistic) return false;
        if (ammoType != null) {
            return ammoType == AmmoItem.AmmoType.AUTO_CANNON || ammoType == AmmoItem.AmmoType.ARTILLERY;
        }
        return Double.isFinite(caliber) && caliber >= 20;
    }
}
