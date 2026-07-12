package org.ywzj.rvp.client.state;

import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Only TV/HITL designate still consumes the base lock key path; vehicle laser designation uses its own key.
 */
public final class RVP_SaclosLockInput {

    private RVP_SaclosLockInput() {}

    public static boolean tryConsumeLockKey(WeaponUnit weaponUnit) {
        if (RVP_ClientHitlState.isDesignateMode()) {
            return true;
        }
        return false;
    }
}
