package org.ywzj.rvp.util;

import net.minecraft.world.entity.Entity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 双端共用座位权限；按实际 seatIndex 识别一号位，不假设列表顺序或控制器角色。 */
public final class RVP_VehicleSeatAccess {
    private RVP_VehicleSeatAccess() {}

    /** 必须正在乘坐本车，且本体座位表的一号位确实登记了该实体。 */
    public static boolean isPrimaryPassenger(Entity passenger, AbstractVehicle vehicle) {
        if (passenger == null || vehicle == null || passenger.getVehicle() != vehicle || vehicle.seats == null)
            return false;
        return vehicle.seats.stream().anyMatch(seat -> seat != null && Integer.valueOf(0).equals(seat.seatIndex)
                && Integer.valueOf(passenger.getId()).equals(seat.passengerId));
    }
}
