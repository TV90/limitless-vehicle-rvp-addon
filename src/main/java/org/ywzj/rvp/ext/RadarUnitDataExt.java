package org.ywzj.rvp.ext;

import org.ywzj.rvp.radar.RVP_RadarHmsMode;

public interface RadarUnitDataExt {
    String ywzj_rvp$getRadarRole();
    String ywzj_rvp$getScanAnimationMode();
    String ywzj_rvp$getNctrMode();
    int ywzj_rvp$getScanPeriodTick();
    boolean ywzj_rvp$isScanLineWhenLocked();
    int ywzj_rvp$getContactHoldTick();
    RVP_RadarHmsMode ywzj_rvp$getHmsMode();
    default boolean ywzj_rvp$isEnableHms() {
        return ywzj_rvp$getHmsMode().isEnabled();
    }
    default boolean ywzj_rvp$isOnlyAcmHms() {
        return ywzj_rvp$getHmsMode().isOnlyAcm();
    }
    float ywzj_rvp$getScanMinHeight();
    float ywzj_rvp$getScanMaxHeight();
    float ywzj_rvp$getChaffResistance();
}
