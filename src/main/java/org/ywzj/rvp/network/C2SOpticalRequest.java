package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_OpticalLock;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.function.Supplier;

public record C2SOpticalRequest(int vehicleId, ResourceLocation weapon, int missileId, int sequence, int targetId, Vec3 groundHint,
                                /** 发起锁定时武器站是否处于高倍率档。 */ boolean zoomed,
                                /** 发起锁定时是否启用热成像，服务端按武器站能力校验。 */ boolean thermal) {
    public static void encode(C2SOpticalRequest msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeResourceLocation(msg.weapon);
        buf.writeVarInt(msg.missileId);
        buf.writeVarInt(msg.sequence);
        buf.writeVarInt(msg.targetId);
        buf.writeNullable(msg.groundHint, (b, v) -> b.writeDouble(v.x).writeDouble(v.y).writeDouble(v.z));
        buf.writeBoolean(msg.zoomed);
        buf.writeBoolean(msg.thermal);
    }

    public static C2SOpticalRequest decode(FriendlyByteBuf buf) {
        return new C2SOpticalRequest(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readNullable(b -> new Vec3(b.readDouble(), b.readDouble(), b.readDouble())), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(C2SOpticalRequest msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.setPacketHandled(true);
        ServerPlayer player = ctx.getSender();
        if (player == null || !(player.getVehicle() instanceof AbstractVehicle vehicle)
                || vehicle.getId() != msg.vehicleId) return;
        if (msg.missileId >= 0) {
            if (player.serverLevel().getEntity(msg.missileId) instanceof RVP_MissileEntity missile) {
                missile.beginOpticalView(player);
            }
            return;
        }
        if (!(vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit unit)) return;
        var weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        if (weapon == null || !msg.weapon.equals(weapon.getData().getWeaponId())
                || weapon.getData().getGuidanceData().getGuidanceType() != RVP_EnumGuidanceType.TV) return;
        // 先由服务器复核实体或地形，再照常同步锁定结果。
        var result = RVP_OpticalLock.tryDesignate(player, unit, weapon.getData(),
                msg.targetId >= 0 ? player.serverLevel().getEntity(msg.targetId) : null,
                msg.targetId < 0 ? msg.groundHint : null, msg.zoomed, msg.thermal);
        S2COpticalFeedback.locked(player, vehicle.getId(), msg.weapon, msg.sequence, result.lock());
        // 两个目标字段都为空时仅清除锁定状态，选中弹种不弹出失败提示。
        if (result.lock() == null && (msg.targetId >= 0 || msg.groundHint != null)) {
            player.displayClientMessage(Component.translatable(result.groundTooDark()
                    ? "ui.rvp.optical.ground_too_dark" : "ui.rvp.optical.lock_failed"), true);
        }
    }
}
