package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.function.Supplier;

public class C2SClearExternalRadarLock {
    public static void encode(C2SClearExternalRadarLock msg, FriendlyByteBuf buf) {}

    public static C2SClearExternalRadarLock decode(FriendlyByteBuf buf) {
        return new C2SClearExternalRadarLock();
    }

    public static void handle(C2SClearExternalRadarLock msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            WeaponUnit weaponUnit = resolveCurrentWeaponUnit(player);
            if (weaponUnit == null) {
                return;
            }
            WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
            if (root != null) {
                RVP_WeaponLockStateTable.clearExternalRadarRequestedEntityId(root);
                RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
            }
            RVP_RadarRoleHelper.clearAllRadarLocks(root);
            root.setLockedEntity(null);
        });
    }

    private static WeaponUnit resolveCurrentWeaponUnit(ServerPlayer player) {
        if (player == null || !(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return null;
        }
        PartUnit<?> partUnit = vehicle.getOwnOperatorUnit(player);
        return partUnit instanceof WeaponUnit weaponUnit ? weaponUnit.getRootParentWeaponUnit() : null;
    }
}
