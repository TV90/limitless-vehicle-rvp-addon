package org.ywzj.rvp.client.state;

import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * SACLOS: cockpit press-R toggles pod laser; HITL DESIGNATE press-R re-selects designated target.
 */
public final class RVP_SaclosLockInput {

    private RVP_SaclosLockInput() {}

    public static boolean tryConsumeLockKey(WeaponUnit weaponUnit) {
        if (RVP_ClientHitlState.isDesignateMode()) {
            return true;
        }
        if (!RVP_ClientSaclosState.isGuiding()) {
            return false;
        }
        RVP_ClientSaclosState.toggleLaser();
        return true;
    }
}
