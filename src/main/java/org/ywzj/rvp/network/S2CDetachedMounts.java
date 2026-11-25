package org.ywzj.rvp.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 记录残件从母车脱离时实际选中的挂件，供晚加入或重新追踪的客户端重建外观。 */
public record S2CDetachedMounts(ResourceLocation dimension, UUID partId, CompoundTag weapons) {
    public static final String NBT_KEY = "RVPDetachedMounts";

    public static void encode(S2CDetachedMounts message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeUUID(message.partId);
        buffer.writeNbt(message.weapons);
    }

    public static S2CDetachedMounts decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        UUID partId = buffer.readUUID();
        CompoundTag weapons = buffer.readNbt();
        if (weapons == null) throw new IllegalArgumentException("Missing detached mount data");
        return new S2CDetachedMounts(dimension, partId, weapons);
    }

    public static void handle(S2CDetachedMounts message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientWreckHiddenBonesCache.acceptMounts(message));
        context.get().setPacketHandled(true);
    }
}
