package org.ywzj.rvp.weapon.damage;

public interface RVP_VehicleHitboxRuntimeAccess {

    void rvp$pushSkipGlobalVehicleHurtScaling();

    void rvp$popSkipGlobalVehicleHurtScaling();

    boolean rvp$shouldSkipGlobalVehicleHurtScaling();

    void rvp$setPendingVehicleHitDisplayDamage(float damage);

    float rvp$getPendingVehicleHitDisplayDamage();

    void rvp$clearPendingVehicleHitDisplayDamage();
}
