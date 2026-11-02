package org.ywzj.rvp.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.maintenance.server.RVP_MaintenanceRuntimeManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 验证一号位权限，并保证服务端伪造请求在读取世界/冷却状态之前即被拒绝。 */
class RVP_VehicleSeatAccessTest {
    private FixedWingVehicle vehicle;
    private ServerPlayer passenger;
    @BeforeAll static void bootstrap() { RVP_ProjectileSimulationBootstrap.initialize(); }
    @BeforeEach void prepare() {
        vehicle = environment(FixedWingVehicle.class);
        passenger = environment(ServerPlayer.class);
        passenger.setId(37);
        write(Entity.class, passenger, "vehicle", vehicle);
    }
    private AbstractVehicle.Seat seat(int index, int occupant) {
        var seat = new AbstractVehicle.Seat(index, null);
        seat.passengerId = occupant;
        return seat;
    }
    @Test void seatIndexRatherThanListOrderDeterminesPrimaryPassenger() {
        vehicle.seats = List.of(seat(2, 50), seat(0, 37));
        assertTrue(RVP_VehicleSeatAccess.isPrimaryPassenger(passenger, vehicle));
    }
    @Test void gunnerCannotStartRepairEvenWithACraftedRequest() {
        vehicle.seats = List.of(seat(0, 42), seat(1, 37));
        assertFalse(RVP_VehicleSeatAccess.isPrimaryPassenger(passenger, vehicle));
        assertFalse(RVP_MaintenanceRuntimeManager.tryStart(passenger, vehicle));
    }
    @Test void staleSeatRecordAfterDismountDoesNotGrantAccess() {
        vehicle.seats = List.of(seat(0, 37));
        write(Entity.class, passenger, "vehicle", null);
        assertFalse(RVP_VehicleSeatAccess.isPrimaryPassenger(passenger, vehicle));
        assertFalse(RVP_MaintenanceRuntimeManager.tryStart(passenger, vehicle));
    }
    @Test void ridingAnotherVehicleCannotRepairTheRequestedVehicle() {
        vehicle.seats = List.of(seat(0, 37));
        write(Entity.class, passenger, "vehicle", environment(FixedWingVehicle.class));
        assertFalse(RVP_MaintenanceRuntimeManager.tryStart(passenger, vehicle));
    }
    @Test void missingSeatOrUnregisteredPassengerIsRejected() {
        vehicle.seats = null;
        assertFalse(RVP_VehicleSeatAccess.isPrimaryPassenger(passenger, vehicle));
        vehicle.seats = List.of(seat(0, -1));
        assertFalse(RVP_MaintenanceRuntimeManager.tryStart(passenger, vehicle));
        assertFalse(RVP_MaintenanceRuntimeManager.tryStart(null, vehicle));
        assertFalse(RVP_MaintenanceRuntimeManager.tryStart(passenger, null));
    }
}
