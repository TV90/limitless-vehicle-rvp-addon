package org.ywzj.rvp.network.remotevisibility;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 弹药超视距视觉完整集合快照。
 *
 * @param dimension 快照所属维度
 * @param entityIds 当前允许扩展绘制的弹药实体 ID 集合
 * @param motorBurnRemainingTicksByEntityId 当前发动机仍在燃烧的弹药实体 ID 到剩余燃烧 Tick 的映射；
 *                                           映射存在即表示仍在燃烧，值允许为 0（燃尽边界 Tick）
 */
public record S2CRemoteAmmoVisualSnapshot(
        ResourceLocation dimension,
        Set<Integer> entityIds,
        Map<Integer, Integer> motorBurnRemainingTicksByEntityId) {
    /** 单份弹药视觉快照允许的最大集合元素数，防止异常载荷无限分配。 */
    private static final int MAX_ENTITY_IDS = 65_536;
    /** 远程尾迹允许同步的最大剩余燃烧时间，单位 Tick；与 RVP 导弹凝结云保护上限一致。 */
    public static final int MAX_MOTOR_BURN_REMAINING_TICKS = 1_200;

    public S2CRemoteAmmoVisualSnapshot {
        Objects.requireNonNull(dimension, "dimension");
        entityIds = immutableIds(entityIds, "entityIds");
        motorBurnRemainingTicksByEntityId = immutableBurnTicks(
                motorBurnRemainingTicksByEntityId, entityIds);
    }

    /** 编码弹药视觉完整集合。 */
    public static void encode(S2CRemoteAmmoVisualSnapshot message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        writeIds(buffer, message.entityIds);
        writeBurnTicks(buffer, message.motorBurnRemainingTicksByEntityId);
    }

    /** 解码弹药视觉完整集合，并限制异常集合大小。 */
    public static S2CRemoteAmmoVisualSnapshot decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        Set<Integer> entityIds = readIds(buffer, "entityIds");
        Map<Integer, Integer> motorBurnRemainingTicksByEntityId = readBurnTicks(buffer, entityIds);
        return new S2CRemoteAmmoVisualSnapshot(dimension, entityIds, motorBurnRemainingTicksByEntityId);
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

    /** 把燃烧中实体及其剩余燃烧 Tick 写入网络缓冲区。 */
    private static void writeBurnTicks(FriendlyByteBuf buffer, Map<Integer, Integer> burnTicksByEntityId) {
        buffer.writeVarInt(burnTicksByEntityId.size());
        for (Map.Entry<Integer, Integer> entry : burnTicksByEntityId.entrySet()) {
            buffer.writeVarInt(entry.getKey());
            buffer.writeVarInt(entry.getValue());
        }
    }

    /** 从网络缓冲区读取燃烧状态，并拒绝越界、重复或未授权的实体 ID。 */
    private static Map<Integer, Integer> readBurnTicks(FriendlyByteBuf buffer, Set<Integer> entityIds) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTITY_IDS) {
            throw new DecoderException("RVP remote ammo motor burn map size exceeds limit");
        }
        Map<Integer, Integer> result = new HashMap<>(Math.min(size, 1024));
        for (int index = 0; index < size; index++) {
            int entityId = buffer.readVarInt();
            int remainingTicks = buffer.readVarInt();
            if (entityId < 0 || !entityIds.contains(entityId)) {
                throw new DecoderException("RVP remote ammo motor burn entity ID is not visible");
            }
            if (remainingTicks < 0 || remainingTicks > MAX_MOTOR_BURN_REMAINING_TICKS) {
                throw new DecoderException("RVP remote ammo remaining motor burn ticks exceeds limit");
            }
            if (result.put(entityId, remainingTicks) != null) {
                throw new DecoderException("RVP remote ammo motor burn map contains duplicate entity ID");
            }
        }
        return result;
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

    /** 创建经边界校验的不可变燃烧时间映射。 */
    private static Map<Integer, Integer> immutableBurnTicks(Map<Integer, Integer> burnTicksByEntityId,
                                                            Set<Integer> entityIds) {
        Objects.requireNonNull(burnTicksByEntityId, "motorBurnRemainingTicksByEntityId");
        if (burnTicksByEntityId.size() > MAX_ENTITY_IDS) {
            throw new IllegalArgumentException("RVP remote ammo motor burn map size exceeds limit");
        }
        Map<Integer, Integer> copy = new HashMap<>(burnTicksByEntityId.size());
        for (Map.Entry<Integer, Integer> entry : burnTicksByEntityId.entrySet()) {
            Integer entityId = entry.getKey();
            Integer remainingTicks = entry.getValue();
            if (entityId == null || remainingTicks == null) {
                throw new IllegalArgumentException("RVP remote ammo motor burn map must not contain null");
            }
            if (!entityIds.contains(entityId)) {
                throw new IllegalArgumentException("RVP remote ammo motor burn entity ID is not visible");
            }
            if (remainingTicks < 0 || remainingTicks > MAX_MOTOR_BURN_REMAINING_TICKS) {
                throw new IllegalArgumentException("RVP remote ammo remaining motor burn ticks exceeds limit");
            }
            copy.put(entityId, remainingTicks);
        }
        return Map.copyOf(copy);
    }
}
