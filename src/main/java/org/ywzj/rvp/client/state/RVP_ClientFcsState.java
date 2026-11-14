package org.ywzj.rvp.client.state;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** 客户端火控系统（FCS）失效查询；失效状态由 S2CBoneModuleState 同步。 */
public final class RVP_ClientFcsState {

    private RVP_ClientFcsState() {}

    public static boolean isDestroyed(@Nullable WeaponUnit unit) {
        return unit != null && isDestroyed(unit.getVehicle());
    }

    public static boolean isDestroyed(@Nullable AbstractVehicle vehicle) {
        return vehicle != null
                && RVP_ClientBoneModuleState.hasInactiveModule(vehicle.getId(), BoneModuleType.FCS);
    }
}
