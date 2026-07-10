package org.ywzj.rvp.client.state;

import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Keep the base fire-control lock key from interfering with TV/HITL designate mode.
 */
public final class RVP_SaclosLockInput {

    private RVP_SaclosLockInput() {}

    public static boolean tryConsumeLockKey(WeaponUnit weaponUnit) {
        return RVP_ClientHitlState.isDesignateMode();
    }
}
