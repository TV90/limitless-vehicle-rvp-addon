package org.ywzj.rvp.network.hitl;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** 附带会话身份的原版完整区块与光照包；客户端继续走原版光照应用链。 */
public record S2CHitlRemoteChunk(UUID session, ClientboundLevelChunkWithLightPacket chunk) {
    public static void encode(S2CHitlRemoteChunk message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.session);
        message.chunk.write(buffer);
    }

    public static S2CHitlRemoteChunk decode(FriendlyByteBuf buffer) {
        return new S2CHitlRemoteChunk(buffer.readUUID(), new ClientboundLevelChunkWithLightPacket(buffer));
    }

    public static void handle(S2CHitlRemoteChunk message, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().setPacketHandled(true);
        // 在客户端主线程验会话再应用区块，不接受过期会话的完整地图数据。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.hitl.RVP_ClientHitlRemoteView.acceptChunk(message));
    }
}
