package org.ywzj.rvp.network.hitl;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.hitl.RVP_HitlRemoteLimits;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** 服务端权威会话和窗口；UUID 防止旧包在切弹/重进世界后污染新观察。 */
public record S2CHitlRemoteView(UUID session, UUID missileUuid, int missileId, ResourceLocation dimension,
                                boolean active, int x, int z, int radius) {
    public static void encode(S2CHitlRemoteView message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.session);
        buffer.writeUUID(message.missileUuid);
        buffer.writeVarInt(message.missileId);
        buffer.writeResourceLocation(message.dimension);
        buffer.writeBoolean(message.active);
        buffer.writeInt(message.x);
        buffer.writeInt(message.z);
        buffer.writeVarInt(message.radius);
    }

    public static S2CHitlRemoteView decode(FriendlyByteBuf buffer) {
        return new S2CHitlRemoteView(buffer.readUUID(), buffer.readUUID(), buffer.readVarInt(),
                buffer.readResourceLocation(), buffer.readBoolean(), buffer.readInt(), buffer.readInt(),
                Math.max(0, Math.min(RVP_HitlRemoteLimits.MAX_RADIUS_CHUNKS, buffer.readVarInt())));
    }

    public static void handle(S2CHitlRemoteView message, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().setPacketHandled(true);
        // 通过分端闭包调用客户端缓存，避免专用服加载任何客户端类型。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.hitl.RVP_ClientHitlRemoteView.accept(message));
    }
}
