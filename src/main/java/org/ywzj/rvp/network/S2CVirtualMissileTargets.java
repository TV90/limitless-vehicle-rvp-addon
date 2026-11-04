package org.ywzj.rvp.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 当前观察者可扫描的虚拟弹体完整集合；实体数据沿用本体远程弹体格式。 */
public record S2CVirtualMissileTargets(
        /** 快照所属维度，禁止跨世界复用目标。 */ ResourceLocation dimension,
        /** 本次仍有效的虚拟目标；缺席的旧目标需要撤销。 */ List<Entry> entries,
        /** 本次已交回真实实体的旧目标 ID，客户端应交接而非判为击毁。 */ Set<Integer> restoredIds) {
    public S2CVirtualMissileTargets {
        entries = List.copyOf(entries);
        restoredIds = Set.copyOf(restoredIds);
    }

    /** 单个目标的会话身份、实体快照与服务端地面高度。 */
    public record Entry(
            /** 本次会话稳定的实体 ID，真实/虚拟交接保持一致。 */ int entityId,
            /** 含 UUID、位置、速度、姿态和武器远程数据的快照。 */ CompoundTag data,
            /** 服务端扫描采用的地面高度，单位格；客户端不自行猜测未加载地形。 */ int groundHeight) {}

    /** 编码服务端已完成资格与扫描包络检查的完整集合。 */
    public static void encode(S2CVirtualMissileTargets message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeCollection(message.entries, (out, entry) -> {
            out.writeVarInt(entry.entityId); out.writeNbt(entry.data); out.writeInt(entry.groundHeight);
        });
        buffer.writeCollection(message.restoredIds, FriendlyByteBuf::writeVarInt);
    }

    /** 解码当前协议；没有旧格式或旧键兼容分支。 */
    public static S2CVirtualMissileTargets decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        List<Entry> entries = buffer.readList(in -> new Entry(in.readVarInt(),
                java.util.Objects.requireNonNull(in.readNbt()), in.readInt()));
        Set<Integer> restoredIds = Set.copyOf(buffer.readList(FriendlyByteBuf::readVarInt));
        return new S2CVirtualMissileTargets(dimension, entries, restoredIds);
    }

    /** 主线程消费者经安全桥转交，专用服务端不加载任何客户端实体缓存类型。 */
    public static void handle(S2CVirtualMissileTargets message, Supplier<NetworkEvent.Context> supplier) {
        // 调用双端安全桥，客户端建立不入世的雷达目标。
        org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptVirtualMissileTargets(message);
        supplier.get().setPacketHandled(true);
    }
}
