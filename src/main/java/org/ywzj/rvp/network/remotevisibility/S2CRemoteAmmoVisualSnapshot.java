package org.ywzj.rvp.network.remotevisibility;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 弹药超视距视觉完整集合快照。
 *
 * @param dimension 快照所属维度
 * @param entityIds 当前允许扩展绘制的弹药实体 ID 集合
 * @param motorBurningEntityIds 当前发动机仍在燃烧的弹药实体 ID 集合
 */
public record S2CRemoteAmmoVisualSnapshot(
        ResourceLocation dimension,
        Set<Integer> entityIds,
        Set<Integer> motorBurningEntityIds) {
    /** 单份弹药视觉快照允许的最大集合元素数，防止异常载荷无限分配。 */
    private static final int MAX_ENTITY_IDS = 65_536;

    public S2CRemoteAmmoVisualSnapshot {
        Objects.requireNonNull(dimension, "dimension");
        entityIds = immutableIds(entityIds, "entityIds");
        motorBurningEntityIds = immutableIds(motorBurningEntityIds, "motorBurningEntityIds");
    }

    /** 编码弹药视觉完整集合。 */
    public static void encode(S2CRemoteAmmoVisualSnapshot message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        writeIds(buffer, message.entityIds);
        writeIds(buffer, message.motorBurningEntityIds);
    }

    /** 解码弹药视觉完整集合，并限制异常集合大小。 */
    public static S2CRemoteAmmoVisualSnapshot decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        Set<Integer> entityIds = readIds(buffer, "entityIds");
        Set<Integer> motorBurningEntityIds = readIds(buffer, "motorBurningEntityIds");
        return new S2CRemoteAmmoVisualSnapshot(dimension, entityIds, motorBurningEntityIds);
    }

    /** 通过公共消费端口分发消息，避免公共网络类加载客户端状态。 */
    public static void handle(S2CRemoteAmmoVisualSnapshot message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 调用 RVP 弹药视觉公共端口，将完整集合交给物理客户端状态表。
            RVP_RemoteAmmoVisualEndpoint.accept(message);
        });
        context.setPacketHandled(true);
    }

    /** 将实体 ID 集合写入网络缓冲区。 */
    private static void writeIds(FriendlyByteBuf buffer, Set<Integer> ids) {
        buffer.writeVarInt(ids.size());
        for (Integer entityId : ids) {
            buffer.writeVarInt(entityId);
        }
    }

    /** 从网络缓冲区读取并校验实体 ID 集合。 */
    private static Set<Integer> readIds(FriendlyByteBuf buffer, String fieldName) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTITY_IDS) {
            throw new DecoderException("RVP remote ammo " + fieldName + " size exceeds limit");
        }
        Set<Integer> ids = new HashSet<>(Math.min(size, 1024));
        for (int index = 0; index < size; index++) {
            int entityId = buffer.readVarInt();
            if (entityId < 0) {
                throw new DecoderException("RVP remote ammo entity ID must be non-negative");
            }
            ids.add(entityId);
        }
        return ids;
    }

    /** 创建防御性不可变实体 ID 集合。 */
    private static Set<Integer> immutableIds(Set<Integer> ids, String fieldName) {
        Objects.requireNonNull(ids, fieldName);
        if (ids.size() > MAX_ENTITY_IDS) {
            throw new IllegalArgumentException("RVP remote ammo " + fieldName + " size exceeds limit");
        }
        for (Integer entityId : ids) {
            if (entityId == null || entityId < 0) {
                throw new IllegalArgumentException("RVP remote ammo entity ID must be non-negative");
            }
        }
        return Set.copyOf(ids);
    }
}
