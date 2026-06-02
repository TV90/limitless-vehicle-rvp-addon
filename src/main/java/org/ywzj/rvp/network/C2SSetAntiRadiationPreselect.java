package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.VehicleAntiRadiationMissile;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;
import java.util.function.Supplier;

public class C2SSetAntiRadiationPreselect {

    public int targetVehicleId;
    public int targetRadarIndex;
    public boolean hasTargetPos;
    public double targetPosX;
    public double targetPosY;
    public double targetPosZ;

    public static C2SSetAntiRadiationPreselect clear() {
        C2SSetAntiRadiationPreselect msg = new C2SSetAntiRadiationPreselect();
        msg.targetVehicleId = -1;
        msg.targetRadarIndex = -1;
        msg.hasTargetPos = false;
        return msg;
    }

    public static C2SSetAntiRadiationPreselect set(int vehicleId, int radarIndex, Vec3 pos) {
        C2SSetAntiRadiationPreselect msg = new C2SSetAntiRadiationPreselect();
        msg.targetVehicleId = vehicleId;
        msg.targetRadarIndex = radarIndex;
        msg.hasTargetPos = true;
        msg.targetPosX = pos.x;
        msg.targetPosY = pos.y;
        msg.targetPosZ = pos.z;
        return msg;
    }

    public static void encode(C2SSetAntiRadiationPreselect msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.targetVehicleId);
        buf.writeInt(msg.targetRadarIndex);
        buf.writeBoolean(msg.hasTargetPos);
        if (msg.hasTargetPos) {
            buf.writeDouble(msg.targetPosX);
            buf.writeDouble(msg.targetPosY);
            buf.writeDouble(msg.targetPosZ);
        }
    }

    public static C2SSetAntiRadiationPreselect decode(FriendlyByteBuf buf) {
        C2SSetAntiRadiationPreselect msg = new C2SSetAntiRadiationPreselect();
        msg.targetVehicleId = buf.readInt();
        msg.targetRadarIndex = buf.readInt();
        msg.hasTargetPos = buf.readBoolean();
        if (msg.hasTargetPos) {
            msg.targetPosX = buf.readDouble();
            msg.targetPosY = buf.readDouble();
            msg.targetPosZ = buf.readDouble();
        }
        return msg;
    }

    public static void handle(C2SSetAntiRadiationPreselect msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
                return;
            }
            for (PartUnit<?> unit : vehicle.getPartUnits()) {
                if (!(unit instanceof WeaponUnit weaponUnit)) {
                    continue;
                }
                if (weaponUnit.getOwner() != player) {
                    continue;
                }
                Optional<AbstractVehicleWeapon<?>> weaponOptional = weaponUnit.getCurrentWeapon();
                if (weaponOptional.isEmpty() || !(weaponOptional.get() instanceof VehicleAntiRadiationMissile arm)) {
                    return;
                }
                if (msg.targetVehicleId < 0 || msg.targetRadarIndex < 0) {
                    arm.clearPreselect();
                } else {
                    @Nullable Vec3 pos = msg.hasTargetPos ? new Vec3(msg.targetPosX, msg.targetPosY, msg.targetPosZ) : null;
                    arm.setPreselect(msg.targetVehicleId, msg.targetRadarIndex, pos);
                }
                return;
            }
        });
    }
}
