package org.ywzj.rvp.network.remotevisibility;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.server.remotevisibility.RVP_EntityTrackingRange;

import java.util.function.Supplier;

/**
 * 客户端在入服和调整视距时上报显示范围；仅改变远距数据准备，不扩大服务端目标授权。
 *
 * @param viewDistanceChunks 当前客户端设置的渲染视距，单位区块，合法范围 2..32
 * @param allowBeyondViewDistance 客户端是否请求超视距载具显示；不能覆盖服务端禁用
 */
public record C2SRemoteViewDistance(int viewDistanceChunks, boolean allowBeyondViewDistance) {
    public C2SRemoteViewDistance {
        if (viewDistanceChunks < 2 || viewDistanceChunks > 32) {
            throw new IllegalArgumentException("Remote view distance must be within 2..32 chunks");
        }
    }

    /** 写入客户端视距。 */
    public static void encode(C2SRemoteViewDistance message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.viewDistanceChunks);
        buffer.writeBoolean(message.allowBeyondViewDistance);
    }

    /** 解码并校验客户端视距。 */
    public static C2SRemoteViewDistance decode(FriendlyByteBuf buffer) {
        return new C2SRemoteViewDistance(buffer.readVarInt(), buffer.readBoolean());
    }

    /** 在网络通道安排的服务端主线程上，仅更新实际发送者的视距。 */
    public static void handle(C2SRemoteViewDistance message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            // 调用服务端每玩家范围表，客户端不能指定或改动其他玩家的交接边界。
            RVP_EntityTrackingRange.updateClientViewDistance(player, message.viewDistanceChunks, message.allowBeyondViewDistance);
        }
        context.setPacketHandled(true);
    }
}
