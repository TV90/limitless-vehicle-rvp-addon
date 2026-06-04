package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.function.Supplier;

public class C2SSetAirburstRange {

    public int vehicleId;
    public int weaponUnitIndex;
    public int weaponIndex;
    public int distanceMeters;

    public static C2SSetAirburstRange of(int vehicleId, int weaponUnitIndex, int weaponIndex, int distanceMeters) {
        C2SSetAirburstRange msg = new C2SSetAirburstRange();
        msg.vehicleId = vehicleId;
        msg.weaponUnitIndex = weaponUnitIndex;
        msg.weaponIndex = weaponIndex;
        msg.distanceMeters = distanceMeters;
        return msg;
    }

    public static void encode(C2SSetAirburstRange msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeVarInt(msg.weaponUnitIndex);
        buf.writeVarInt(msg.weaponIndex);
        buf.writeVarInt(msg.distanceMeters);
    }

    public static C2SSetAirburstRange decode(FriendlyByteBuf buf) {
        C2SSetAirburstRange msg = new C2SSetAirburstRange();
        msg.vehicleId = buf.readVarInt();
        msg.weaponUnitIndex = buf.readVarInt();
        msg.weaponIndex = buf.readVarInt();
        msg.distanceMeters = buf.readVarInt();
        return msg;
    }

    public static void handle(C2SSetAirburstRange msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Entity entity = player.level().getEntity(msg.vehicleId);
            if (!(entity instanceof AbstractVehicle vehicle)) {
                return;
            }
            if (!vehicle.getPassengers().contains(player)) {
                return;
            }
            WeaponUnit unit = findWeaponUnit(vehicle, msg.weaponUnitIndex);
            if (unit == null) {
                return;
            }
            AbstractVehicleWeapon<?> weapon = unit.getIndexedWeapons().stream()
                    .filter(w -> w.getIndex() == msg.weaponIndex)
                    .findFirst()
                    .orElse(null);
            if (!(weapon instanceof RVP_WeaponBase rvp)) {
                return;
            }
            RVP_FuseData fuse = rvp.getData().getFuseData();
            if (!fuse.isProgrammableAirburst()) {
                return;
            }
            int dist = msg.distanceMeters;
            if (dist > 0 && (dist <= fuse.getAirburstMeasureMin() || dist >= fuse.getAirburstMeasureMax())) {
                return;
            }
            RVP_AirburstRangeStore.set(vehicle, unit, msg.weaponIndex, dist);
        });
    }

    private static WeaponUnit findWeaponUnit(AbstractVehicle vehicle, int index) {
        for (var part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.getIndex() == index) {
                return unit;
            }
        }
        return null;
    }
}
