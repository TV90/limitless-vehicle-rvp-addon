package org.ywzj.rvp.client.seeker;

import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** Client-only entry points invoked from common code via {@code DistExecutor}. */
public final class RVP_ClientSeekerBridge {

    private RVP_ClientSeekerBridge() {}

    public static void onWeaponSelected(WeaponUnit unit, RVP_WeaponData data) {
        RVP_ClientSeekerController.onWeaponSelected(unit, data);
    }

    public static void onWeaponDeselected(WeaponUnit unit) {
        RVP_ClientSeekerController.onWeaponDeselected(unit);
    }

    public static void onWeaponFired(WeaponUnit unit) {
        RVP_ClientSeekerController.onWeaponFired(unit);
    }
}
