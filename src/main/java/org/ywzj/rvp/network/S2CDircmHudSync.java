package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：DIRCM 各通道状态（每通道 = 一个照射骨骼）。
 * 客户端 {@code RVP_DircmHudState} + {@code RVP_DircmHudOverlay} 渲染左右通道照射/充能。
 * 消费端经 {@link DistExecutor} 公共端口分发（不直接 import 客户端类型，符合架构测试）。
 */
public final class S2CDircmHudSync {

    private final int vehicleEntityId;
    private final List<String> boneNames;
    private final List<Integer> targetIds;
    private final List<Integer> chargeRemains;

    public S2CDircmHudSync(int vehicleEntityId, List<String> boneNames,
                           List<Integer> targetIds, List<Integer> chargeRemains) {
        this.vehicleEntityId = vehicleEntityId;
        this.boneNames = boneNames;
        this.targetIds = targetIds;
        this.chargeRemains = chargeRemains;
    }

    public static void encode(S2CDircmHudSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleEntityId);
        buf.writeVarInt(msg.boneNames.size());
        for (int i = 0; i < msg.boneNames.size(); i++) {
            buf.writeUtf(msg.boneNames.get(i));
            buf.writeVarInt(msg.targetIds.get(i));
            buf.writeVarInt(msg.chargeRemains.get(i));
        }
    }

    public static S2CDircmHudSync decode(FriendlyByteBuf buf) {
        int vehicleEntityId = buf.readVarInt();
        int count = buf.readVarInt();
        List<String> bones = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        List<Integer> charges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bones.add(buf.readUtf(64));
            targets.add(buf.readVarInt());
            charges.add(buf.readVarInt());
        }
        return new S2CDircmHudSync(vehicleEntityId, bones, targets, charges);
    }

    public static void handle(S2CDircmHudSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_DircmHudState.update(
                        msg.vehicleEntityId, msg.boneNames, msg.targetIds, msg.chargeRemains)));
        ctx.get().setPacketHandled(true);
    }
}