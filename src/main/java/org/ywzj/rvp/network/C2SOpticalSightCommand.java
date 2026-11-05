package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.vehicle.RVP_OpticalWeaponUnit;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.UUID;
import java.util.function.Supplier;

/** 新版吊舱的绝对视线请求；服务端自行执行转速、动力与限位。 */
public record C2SOpticalSightCommand(
        /** 请求所属维度，切世界后的迟到包不得接管新世界。 */ net.minecraft.resources.ResourceLocation dimension,
        /** 所属载具实体 ID。 */ int vehicleId,
        /** 实体身份，防止 ID 复用。 */ UUID vehicleUUID,
        /** 本车武器站索引。 */ int stationIndex,
        /** 本次本地操作会话。 */ UUID session,
        /** 单调递增的输入序号。 */ long sequence,
        /** 请求单位方向；稳定器开启为世界方向，关闭且无真实点时为基座局部方向。 */ Vec3 direction,
        /** 真实世界点；未知距离方向锁为空。 */ @Nullable Vec3 point) {
    public static void encode(C2SOpticalSightCommand m, FriendlyByteBuf b) {
        b.writeResourceLocation(m.dimension);
        b.writeInt(m.vehicleId); b.writeUUID(m.vehicleUUID); b.writeVarInt(m.stationIndex);
        b.writeUUID(m.session); b.writeLong(m.sequence);
        b.writeDouble(m.direction.x); b.writeDouble(m.direction.y); b.writeDouble(m.direction.z);
        b.writeBoolean(m.point != null);
        if (m.point != null) { b.writeDouble(m.point.x); b.writeDouble(m.point.y); b.writeDouble(m.point.z); }
    }
    public static C2SOpticalSightCommand decode(FriendlyByteBuf b) {
        var dimension = b.readResourceLocation();
        int id = b.readInt(); UUID uuid = b.readUUID(); int station = b.readVarInt(); UUID session = b.readUUID(); long seq = b.readLong();
        Vec3 direction = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        Vec3 point = b.readBoolean() ? new Vec3(b.readDouble(), b.readDouble(), b.readDouble()) : null;
        return new C2SOpticalSightCommand(dimension, id, uuid, station, session, seq, direction, point);
    }
    public static void handle(C2SOpticalSightCommand m, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        var player = context.getSender();
        if (player != null && player.level().dimension().location().equals(m.dimension)
                && player.level().getEntity(m.vehicleId) instanceof AbstractVehicle vehicle
                && vehicle.getUUID().equals(m.vehicleUUID) && m.stationIndex >= 0 && m.stationIndex < vehicle.getPartUnits().size()
                && vehicle.getPartUnits().get(m.stationIndex) instanceof RVP_OpticalWeaponUnit station) {
            // 调用本站权限和顺序检查，禁止替其他玩家或其他类型观瞄发送控制。
            station.acceptCommand(player, m.session, m.sequence, m.direction, m.point);
        }
        context.setPacketHandled(true);
    }
}
