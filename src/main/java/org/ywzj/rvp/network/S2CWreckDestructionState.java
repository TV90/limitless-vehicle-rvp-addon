package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 地面载具击毁时间表；仅 S2C，数据与客户端实现通过双端安全桥隔离。
 * @param dimension 所属维度，避免切维度后迟到包污染当前世界
 * @param vehicleId 实体 UUID，避免数字实体 ID 复用串车
 * @param state 服务端已经抽取并保存的结果与时刻
 */
public record S2CWreckDestructionState(ResourceLocation dimension, UUID vehicleId, RVP_WreckDestructionState state) {
    /** 使用当前 NBT schema 编码，不同步粒子、模型或逐车资源。 */
    public static void encode(S2CWreckDestructionState message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeUUID(message.vehicleId);
        // 调用本项目统一编码，持久化与网络状态使用同一字段语义。
        buffer.writeNbt(message.state.toTag());
    }

    /** 解码并严格验证当前时间表，不接受缺失字段和未知枚举。 */
    public static S2CWreckDestructionState decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        UUID vehicleId = buffer.readUUID();
        // 调用本项目统一解码，非法快照由网络层拒绝。
        RVP_WreckDestructionState state = RVP_WreckDestructionState.fromTag(buffer.readNbt());
        if (state == null) throw new IllegalArgumentException("Invalid wreck destruction snapshot");
        return new S2CWreckDestructionState(dimension, vehicleId, state);
    }

    /** 消息注册为 consumerMainThread，桥内只更新客户端缓存，不产生服务端动作。 */
    public static void handle(S2CWreckDestructionState message, Supplier<NetworkEvent.Context> contextSupplier) {
        // 调用本项目安全桥；公共网络类不会加载 Minecraft 或客户端效果控制器。
        org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptWreckDestructionState(message);
        contextSupplier.get().setPacketHandled(true);
    }
}
