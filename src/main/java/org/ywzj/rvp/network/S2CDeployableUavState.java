package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 服务端推送母车选项、冷却、部署及锁原因的权威状态。 */
public record S2CDeployableUavState(UUID parentUuid, String selectedOptionId, long revision,
                                    int remainingCooldownTick, boolean deployed, String lockedReason) {
    /** 编码母车 UUID 和当前权威状态。 */
    public static void encode(S2CDeployableUavState msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.parentUuid()); buf.writeUtf(msg.selectedOptionId(), 128); buf.writeLong(msg.revision());
        buf.writeVarInt(msg.remainingCooldownTick()); buf.writeBoolean(msg.deployed()); buf.writeUtf(msg.lockedReason(), 64);
    }
    /** 解码服务端状态消息。 */
    public static S2CDeployableUavState decode(FriendlyByteBuf buf) {
        return new S2CDeployableUavState(buf.readUUID(), buf.readUtf(128), buf.readLong(), buf.readVarInt(),
                buf.readBoolean(), buf.readUtf(64));
    }
    /** 经 DistExecutor 调客户端 cache，公共网络类不直接加载 client-only 类型。 */
    public static void handle(S2CDeployableUavState msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get(); context.setPacketHandled(true);
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_DeployableUavClientStateCache.applySnapshot(msg.parentUuid(),
                        new org.ywzj.rvp.client.state.RVP_DeployableUavClientStateCache.StateSnapshot(
                                msg.selectedOptionId(), msg.revision(), msg.remainingCooldownTick(),
                                msg.deployed(), msg.lockedReason()))));
    }
}