package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.function.Supplier;

public class C2SRequestExternalRadarLock {
    public int entityId;

    public C2SRequestExternalRadarLock() {}

    public C2SRequestExternalRadarLock(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(C2SRequestExternalRadarLock msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static C2SRequestExternalRadarLock decode(FriendlyByteBuf buf) {
        return new C2SRequestExternalRadarLock(buf.readInt());
    }

    public static void handle(C2SRequestExternalRadarLock msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            WeaponUnit weaponUnit = resolveCurrentWeaponUnit(player);
            if (player == null || weaponUnit == null || msg.entityId == Integer.MIN_VALUE) {
                return;
            }
            Entity target = player.serverLevel().getEntity(msg.entityId);
            if (target == null || !target.isAlive()) {
                return;
            }
            WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
            RVP_RadarRoleHelper.clearAllRadarLocks(root);
            if (root instanceof WeaponUnitExternalRadarLockExt ext) {
                ext.ywzj_rvp$setExternalRadarRequestedEntityId(target.getId());
                ext.ywzj_rvp$clearExternalRadarLockedEntityId();
            }
            root.setFocusLockPos(null);
            root.setLockedEntity(target);
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
