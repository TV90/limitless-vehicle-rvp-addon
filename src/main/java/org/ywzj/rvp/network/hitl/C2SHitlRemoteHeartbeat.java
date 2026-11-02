package org.ywzj.rvp.network.hitl;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService;
import java.util.UUID;
import java.util.function.Supplier;

/** 观察心跳和显式退出；指定模式即使暂时不移动鼠标也保持有效会话。 */
public record C2SHitlRemoteHeartbeat(UUID session, boolean stop) {
    public static void encode(C2SHitlRemoteHeartbeat message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.session);
        buffer.writeBoolean(message.stop);
    }

    public static C2SHitlRemoteHeartbeat decode(FriendlyByteBuf buffer) {
        return new C2SHitlRemoteHeartbeat(buffer.readUUID(), buffer.readBoolean());
    }

    public static void handle(C2SHitlRemoteHeartbeat message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.setPacketHandled(true);
        // 服务端按连接玩家、会话 UUID、实体身份和距离统一校验。
        RVP_HitlRemoteViewService.heartbeat(context.getSender(), message.session, message.stop);
    }
}
