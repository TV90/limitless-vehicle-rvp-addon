package org.ywzj.rvp.network.gunner;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 登录与 datapack reload 后下发的 Gunner Profile ID 完整快照。 */
public record S2CGunnerProfileSnapshot(
        /** 服务端最近一次成功原子发布的代次。 */ long generation,
        /** 该代次内服务端权威 Profile 资源 ID 列表。 */ List<ResourceLocation> profileIds) {
    /** 单个快照允许的 Profile 数量上限，用于防御异常数据包。 */
    private static final int MAX_PROFILES = 256;
    public S2CGunnerProfileSnapshot { profileIds = List.copyOf(profileIds); }
    public static void encode(S2CGunnerProfileSnapshot message, FriendlyByteBuf buffer) {
        if (message.profileIds.size() > MAX_PROFILES) throw new IllegalArgumentException("Gunner Profile 超过 256 项");
        buffer.writeLong(message.generation);
        buffer.writeVarInt(message.profileIds.size());
        message.profileIds.forEach(buffer::writeResourceLocation);
    }
    public static S2CGunnerProfileSnapshot decode(FriendlyByteBuf buffer) {
        long generation = buffer.readLong();
        int count = buffer.readVarInt();
        if (generation < 0L || count < 0 || count > MAX_PROFILES) throw new DecoderException("非法 Gunner Profile 快照头");
        List<ResourceLocation> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ResourceLocation id = buffer.readResourceLocation();
            if (ids.contains(id)) throw new DecoderException("Gunner Profile ID 重复: " + id);
            ids.add(id);
        }
        return new S2CGunnerProfileSnapshot(generation, ids);
    }
    public static void handle(S2CGunnerProfileSnapshot message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> RVP_GunnerProfileClientEndpoint.accept(message));
        context.setPacketHandled(true);
    }
}
