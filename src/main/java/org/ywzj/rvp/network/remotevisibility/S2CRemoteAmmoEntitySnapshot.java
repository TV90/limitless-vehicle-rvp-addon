package org.ywzj.rvp.network.remotevisibility;

import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * RVP 自有的远程弹药实体克隆快照。
 *
 * <p>该消息只负责把弹体放入本体客户端的远程实体表，不经过本体的载具超视距渲染协议。</p>
 *
 * @param dimension 快照所属维度。
 * @param entries 当前需要创建或更新的弹药实体条目。
 */
public record S2CRemoteAmmoEntitySnapshot(ResourceLocation dimension, List<Entry> entries) {
    /** 单份弹药实体快照允许的最大条目数，防止异常网络包造成无限分配。 */
    public static final int MAX_ENTRIES = 65_536;

    public S2CRemoteAmmoEntitySnapshot {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("RVP remote ammo entity entry count exceeds " + MAX_ENTRIES);
        }
        entries = List.copyOf(entries);
    }

    /** 编码 RVP 弹药实体克隆快照。 */
    public static void encode(S2CRemoteAmmoEntitySnapshot message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeVarInt(message.entries.size());
        for (Entry entry : message.entries) {
            writeEntry(buffer, entry);
        }
    }

    /** 解码并校验 RVP 弹药实体克隆快照。 */
    public static S2CRemoteAmmoEntitySnapshot decode(FriendlyByteBuf buffer) {
        try {
            ResourceLocation dimension = buffer.readResourceLocation();
            int entryCount = buffer.readVarInt();
            if (entryCount < 0 || entryCount > MAX_ENTRIES) {
                throw new DecoderException("RVP remote ammo entity entry count exceeds " + MAX_ENTRIES);
            }
            List<Entry> entries = new ArrayList<>(entryCount);
            for (int index = 0; index < entryCount; index++) {
                entries.add(readEntry(buffer));
            }
            return new S2CRemoteAmmoEntitySnapshot(dimension, entries);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Invalid RVP remote ammo entity snapshot", exception);
        }
    }

    /** 通过公共消费端口交给客户端克隆状态，避免公共网络类直接依赖客户端类。 */
    public static void handle(S2CRemoteAmmoEntitySnapshot message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 调用 RVP 弹药实体公共消费端口，把快照交给客户端远程实体表。
            RVP_RemoteAmmoEntityEndpoint.accept(message);
        });
        context.setPacketHandled(true);
    }

    /** 编码单个弹药实体条目。 */
    private static void writeEntry(FriendlyByteBuf buffer, Entry entry) {
        buffer.writeInt(entry.entityId);
        buffer.writeResourceLocation(entry.entityType);
        writeVec3(buffer, entry.position);
        writeVec3(buffer, entry.velocity);
        buffer.writeNbt(entry.data);
    }

    /** 解码单个弹药实体条目。 */
    private static Entry readEntry(FriendlyByteBuf buffer) {
        int entityId = buffer.readInt();
        ResourceLocation entityType = buffer.readResourceLocation();
        Vec3 position = readVec3(buffer);
        Vec3 velocity = readVec3(buffer);
        CompoundTag data = buffer.readNbt();
        if (data == null) {
            throw new DecoderException("RVP remote ammo entity data must not be null");
        }
        return new Entry(entityId, entityType, position, velocity, data);
    }

    /** 写入实体位置或速度向量。 */
    private static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x);
        buffer.writeDouble(value.y);
        buffer.writeDouble(value.z);
    }

    /** 读取实体位置或速度向量。 */
    private static Vec3 readVec3(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    /**
     * 单个远程弹药实体条目。
     *
     * @param entityId 服务端实体 ID。
     * @param entityType 本体注册的实体类型 ID。
     * @param position 服务端实体位置，单位格。
     * @param velocity 服务端实体速度，单位格每 Tick。
     * @param data 实体无 ID NBT，以及 RVP 远程 Tick 扩展数据。
     */
    public record Entry(int entityId, ResourceLocation entityType, Vec3 position,
                        Vec3 velocity, CompoundTag data) {
        public Entry {
            if (entityId < 0) {
                throw new IllegalArgumentException("RVP remote ammo entity ID must be non-negative");
            }
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(data, "data");
        }
    }
}
