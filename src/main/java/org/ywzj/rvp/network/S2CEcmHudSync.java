package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：ECM 各通道状态（每通道 = 一个 ecm_passive 骨块）。
 * 客户端 {@code RVP_EcmHudState} + {@code RVP_EcmHudOverlay} 渲染干扰/充能。
 */
public final class S2CEcmHudSync {

    private final int vehicleEntityId;
    private final List<String> boneNames;
    private final List<String> displayNames;
    private final List<Integer> decoyCounts;
    private final List<Integer> chargeRemains;

    public S2CEcmHudSync(int vehicleEntityId, List<String> boneNames, List<String> displayNames,
                         List<Integer> decoyCounts, List<Integer> chargeRemains) {
        this.vehicleEntityId = vehicleEntityId;
        this.boneNames = boneNames;
        this.displayNames = displayNames;
        this.decoyCounts = decoyCounts;
        this.chargeRemains = chargeRemains;
    }

    public static void encode(S2CEcmHudSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleEntityId);
        buf.writeVarInt(msg.boneNames.size());
        for (int i = 0; i < msg.boneNames.size(); i++) {
            buf.writeUtf(msg.boneNames.get(i));
            buf.writeUtf(msg.displayNames.get(i));
            buf.writeVarInt(msg.decoyCounts.get(i));
            buf.writeVarInt(msg.chargeRemains.get(i));
        }
    }

    public static S2CEcmHudSync decode(FriendlyByteBuf buf) {
        int vehicleEntityId = buf.readVarInt();
        int count = buf.readVarInt();
        List<String> bones = new ArrayList<>();
        List<String> displays = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Integer> charges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bones.add(buf.readUtf(64));
            displays.add(buf.readUtf(64));
            counts.add(buf.readVarInt());
            charges.add(buf.readVarInt());
        }
        return new S2CEcmHudSync(vehicleEntityId, bones, displays, counts, charges);
    }

    public static void handle(S2CEcmHudSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_EcmHudState.update(
                        msg.vehicleEntityId, msg.boneNames, msg.displayNames,
                        msg.decoyCounts, msg.chargeRemains)));
        ctx.get().setPacketHandled(true);
    }
}
