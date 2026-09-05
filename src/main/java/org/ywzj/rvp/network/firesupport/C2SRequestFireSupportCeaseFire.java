package org.ywzj.rvp.network.firesupport;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionManager;
import org.ywzj.rvp.network.RVP_Network;

import java.util.UUID;
import java.util.function.Supplier;

/** 所有者请求延迟停火的最小消息；服务端不采信任何手或实例字段。 */
public record C2SRequestFireSupportCeaseFire(
        /** 要求停火的任务 UUID。 */ UUID missionId,
        /** 停火操作独立幂等 nonce。 */ UUID nonce) {
    /** 编码两个固定长度 UUID。 */
    public static void encode(C2SRequestFireSupportCeaseFire message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.missionId);
        buffer.writeUUID(message.nonce);
    }

    /** 解码两个固定长度 UUID。 */
    public static C2SRequestFireSupportCeaseFire decode(FriendlyByteBuf buffer) {
        return new C2SRequestFireSupportCeaseFire(buffer.readUUID(), buffer.readUUID());
    }

    /** 在服务端主线程核验任务所有者、阶段和当前两只手的绑定实例。 */
    public static void handle(C2SRequestFireSupportCeaseFire message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() == null) return;
            // 调用本项目任务管理器：即时读取服务端主/副手并固定最早停火生效 Tick。
            RVP_FireSupportMissionManager.CeaseFireResult result =
                    RVP_FireSupportMissionManager.requestCeaseFire(context.getSender(), message.missionId, message.nonce);
            // 调用本项目结果消息复用停火响应，向请求玩家返回明确拒绝原因或生效 Tick。
            RVP_Network.CHANNEL.sendTo(S2CFireSupportRequestResult.fromCeaseFire(
                            message.nonce, message.missionId, result),
                    context.getSender().connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        });
        context.setPacketHandled(true);
    }
}
