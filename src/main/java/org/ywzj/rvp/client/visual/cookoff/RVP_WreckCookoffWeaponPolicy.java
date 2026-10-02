package org.ywzj.rvp.client.visual.cookoff;

import org.ywzj.vehicle.item.AmmoItem;

/** 火炮识别的纯规则：优先现有装填弹药类型，避免把曳光可视口径误当实际口径。 */
public final class RVP_WreckCookoffWeaponPolicy {
    private RVP_WreckCookoffWeaponPolicy() {}

    /** 仅弹道火炮且弹体口径达到 45 mm 才允许炮口喷燃；弹药类型不能绕过口径阈值。 */
    public static boolean accepts(boolean ballistic, AmmoItem.AmmoType ammoType, double caliber) {
        if (!ballistic || !Double.isFinite(caliber) || caliber < 45.0) return false;
        if (ammoType != null) {
            return ammoType == AmmoItem.AmmoType.AUTO_CANNON || ammoType == AmmoItem.AmmoType.ARTILLERY;
        }
        return true;
    }
}
