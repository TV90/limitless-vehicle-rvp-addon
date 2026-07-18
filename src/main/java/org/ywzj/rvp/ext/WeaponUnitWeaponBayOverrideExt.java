package org.ywzj.rvp.ext;

public interface WeaponUnitWeaponBayOverrideExt {

    void ywzj_rvp$markWeaponBayManualOverride(int primaryIndex, int secondaryIndex);

    boolean ywzj_rvp$isWeaponBayManualOverrideActive(int primaryIndex, int secondaryIndex);

    void ywzj_rvp$clearWeaponBayManualOverride();
}
