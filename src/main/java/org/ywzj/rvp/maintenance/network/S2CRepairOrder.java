package org.ywzj.rvp.maintenance.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientRepairOrderState;

import java.util.List;
import java.util.function.Supplier;

/** 服务器将全车共用的完整优先顺序同步给乘员；不携带维修执行指令。 */
public record S2CRepairOrder(int vehicleId, List<String> eraBones, List<String> deviceBones) {
    public S2CRepairOrder {
        eraBones = List.copyOf(eraBones);
        deviceBones = List.copyOf(deviceBones);
    }

    public static void encode(S2CRepairOrder msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeVarInt(msg.eraBones.size());
        for (String bone : msg.eraBones) buf.writeUtf(bone, 256);
        buf.writeVarInt(msg.deviceBones.size());
        for (String bone : msg.deviceBones) buf.writeUtf(bone, 256);
    }

    public static S2CRepairOrder decode(FriendlyByteBuf buf) {
        return new S2CRepairOrder(buf.readVarInt(), C2SSetRepairOrder.readBones(buf),
                C2SSetRepairOrder.readBones(buf));
    }

    public static void handle(S2CRepairOrder msg, Supplier<NetworkEvent.Context> context) {
        context.get().setPacketHandled(true);
        context.get().enqueueWork(() -> apply(msg));
    }

    @OnlyIn(Dist.CLIENT)
    private static void apply(S2CRepairOrder msg) {
        // 复用客户端顺序表，使已打开的面板下一轮刷新能够看见服务端结果。
        RVP_ClientRepairOrderState.set(msg.vehicleId, msg.eraBones, msg.deviceBones);
    }
}
