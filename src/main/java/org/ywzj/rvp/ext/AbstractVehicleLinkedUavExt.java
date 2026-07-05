package org.ywzj.rvp.ext;

import java.util.UUID;

/**
 * 为载具实例附加 deployable UAV 主从关系与实例级 UAV 状态。
 */
public interface AbstractVehicleLinkedUavExt {
    UUID ywzj_rvp$getLinkedParentVehicleUuid();
    void ywzj_rvp$setLinkedParentVehicleUuid(UUID uuid);

    UUID ywzj_rvp$getLinkedChildVehicleUuid();
    void ywzj_rvp$setLinkedChildVehicleUuid(UUID uuid);

    UUID ywzj_rvp$getLinkedLauncherVehicleUuid();
    void ywzj_rvp$setLinkedLauncherVehicleUuid(UUID uuid);

    boolean ywzj_rvp$isDeployableUavInstance();
    void ywzj_rvp$setDeployableUavInstance(boolean value);

    boolean ywzj_rvp$isDeployableUavControlSwitchAllowed();
    void ywzj_rvp$setDeployableUavControlSwitchAllowed(boolean value);

    int ywzj_rvp$getReturnSeatIndex();
    void ywzj_rvp$setReturnSeatIndex(int seatIndex);

    String ywzj_rvp$getDeployableUavRole();
    void ywzj_rvp$setDeployableUavRole(String role);

    String ywzj_rvp$getDatalinkRole();
    void ywzj_rvp$setDatalinkRole(String role);
}
