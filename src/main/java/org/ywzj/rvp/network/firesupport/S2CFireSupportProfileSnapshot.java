package org.ywzj.rvp.network.firesupport;

import io.netty.handler.codec.DecoderException;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileNetworkCodec;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportSnapshot;

/** 登录与 reload 下发的完整、规范化炮火 profile 快照。 */
public record S2CFireSupportProfileSnapshot(
        /** 服务端单调递增 revision。 */ long revision,
        /** profile ID 到规范化当前 schema JSON 的不可变映射。 */ Map<ResourceLocation, String> profiles) {
    /** 单快照最大 profile 数。 */ private static final int MAX_PROFILES = 128;
    /** 单 profile JSON 最大 UTF-8 字节预算。 */ private static final int MAX_PROFILE_JSON = 1_048_576;

    public S2CFireSupportProfileSnapshot { profiles = Map.copyOf(profiles); }

    /** 从服务端原子快照构造网络视图。 */
    public static S2CFireSupportProfileSnapshot from(RVP_FireSupportSnapshot snapshot) {
        Map<ResourceLocation, String> payload = new LinkedHashMap<>();
        snapshot.profiles().forEach((id, profile) -> payload.put(id, RVP_FireSupportProfileNetworkCodec.encode(profile)));
        return new S2CFireSupportProfileSnapshot(snapshot.revision(), payload);
    }

    /** 写入显式有界完整快照。 */
    public static void encode(S2CFireSupportProfileSnapshot message, FriendlyByteBuf buffer) {
        if (message.profiles.size() > MAX_PROFILES) throw new IllegalArgumentException("炮火 profile 超过 128 项");
        buffer.writeLong(message.revision);
        buffer.writeVarInt(message.profiles.size());
        message.profiles.forEach((id, json) -> {
            buffer.writeResourceLocation(id);
            buffer.writeUtf(json, MAX_PROFILE_JSON);
        });
    }

    /** 在分配 Map 前校验 profile 数和每段字符串长度。 */
    public static S2CFireSupportProfileSnapshot decode(FriendlyByteBuf buffer) {
        long revision = buffer.readLong();
        int count = buffer.readVarInt();
        if (revision < 0 || count < 0 || count > MAX_PROFILES) throw new DecoderException("非法炮火 profile 快照头");
        Map<ResourceLocation, String> profiles = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            ResourceLocation id = buffer.readResourceLocation();
            if (profiles.putIfAbsent(id, buffer.readUtf(MAX_PROFILE_JSON)) != null) {
                throw new DecoderException("炮火 profile ID 重复");
            }
        }
        return new S2CFireSupportProfileSnapshot(revision, profiles);
    }

    /** 经公共端口交给客户端状态。 */
    public static void handle(S2CFireSupportProfileSnapshot message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> RVP_FireSupportClientEndpoint.accept(message));
        context.setPacketHandled(true);
    }
}
