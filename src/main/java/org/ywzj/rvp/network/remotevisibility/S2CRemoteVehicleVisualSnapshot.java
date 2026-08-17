package org.ywzj.rvp.network.remotevisibility;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 服务端到客户端的远距载具视觉完整集合快照。
 *
 * @param dimension 快照所属维度
 * @param serverGameTime 服务端生成快照时的世界时间，单位 tick
 * @param sequence 当前玩家连接内单调递增且非负的快照序号
 * @param entries 当前服务端授权的完整载具视觉条目集合
 */
public record S2CRemoteVehicleVisualSnapshot(
        ResourceLocation dimension,
        long serverGameTime,
        long sequence,
        List<Entry> entries) {
    /** 单份载具视觉快照允许的最大条目数。 */
    public static final int MAX_ENTRIES = 1024;
    /** 条目损毁状态位。 */
    private static final int FLAG_DESTROYED = 1;
    /** 条目发动机开启状态位。 */
    private static final int FLAG_ENGINE_ON = 1 << 1;

    public S2CRemoteVehicleVisualSnapshot {
        Objects.requireNonNull(dimension, "dimension");
        if (sequence < 0L) {
            throw new IllegalArgumentException("RVP remote vehicle sequence must be non-negative");
        }
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("RVP remote vehicle entry count exceeds 1024");
        }
        entries = List.copyOf(entries);
    }

    /** 将远距载具视觉完整集合写入网络缓冲区。 */
    public static void encode(S2CRemoteVehicleVisualSnapshot message, FriendlyByteBuf buffer) {
        if (message.entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("RVP remote vehicle entry count exceeds 1024");
        }
        buffer.writeResourceLocation(message.dimension);
        buffer.writeLong(message.serverGameTime);
        buffer.writeLong(message.sequence);
        buffer.writeVarInt(message.entries.size());
        for (Entry entry : message.entries) {
            writeEntry(buffer, entry);
        }
    }

    /** 从网络缓冲区解码并严格校验远距载具视觉完整集合。 */
    public static S2CRemoteVehicleVisualSnapshot decode(FriendlyByteBuf buffer) {
        try {
            ResourceLocation dimension = buffer.readResourceLocation();
            long serverGameTime = buffer.readLong();
            long sequence = buffer.readLong();
            if (sequence < 0L) {
                throw new DecoderException("RVP remote vehicle sequence must be non-negative");
            }
            int entryCount = buffer.readVarInt();
            if (entryCount < 0 || entryCount > MAX_ENTRIES) {
                throw new DecoderException("RVP remote vehicle entry count exceeds 1024");
            }
            List<Entry> entries = new java.util.ArrayList<>(entryCount);
            for (int index = 0; index < entryCount; index++) {
                entries.add(readEntry(buffer));
            }
            return new S2CRemoteVehicleVisualSnapshot(dimension, serverGameTime, sequence, entries);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Invalid RVP remote vehicle visual snapshot", exception);
        }
    }

    /** 通过公共 NOOP 端口分发快照，阶段 B 再安装真实客户端状态消费者。 */
    public static void handle(S2CRemoteVehicleVisualSnapshot message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 调用 RVP 载具视觉公共端口，保持公共网络层与客户端代理实现解耦。
            RVP_RemoteVehicleVisualEndpoint.accept(message);
        });
        context.setPacketHandled(true);
    }

    /** 编码单个载具视觉条目。 */
    private static void writeEntry(FriendlyByteBuf buffer, Entry entry) {
        buffer.writeVarInt(entry.entityId);
        buffer.writeResourceLocation(entry.entityType);
        buffer.writeResourceLocation(entry.vehicleId);
        buffer.writeResourceLocation(entry.displayId);
        writeVec3(buffer, entry.position);
        writeVec3(buffer, entry.velocity);
        buffer.writeFloat(entry.xRot);
        buffer.writeFloat(entry.yRot);
        buffer.writeFloat(entry.zRot);
        buffer.writeDouble(entry.heightAboveGround);
        int flags = (entry.destroyed ? FLAG_DESTROYED : 0)
                | (entry.engineOn ? FLAG_ENGINE_ON : 0);
        buffer.writeByte(flags);
        buffer.writeFloat(entry.power);
        buffer.writeFloat(entry.engineSpeed);
    }

    /** 解码并校验单个载具视觉条目。 */
    private static Entry readEntry(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        ResourceLocation entityType = buffer.readResourceLocation();
        ResourceLocation vehicleId = buffer.readResourceLocation();
        ResourceLocation displayId = buffer.readResourceLocation();
        Vec3 position = readVec3(buffer);
        Vec3 velocity = readVec3(buffer);
        float xRot = buffer.readFloat();
        float yRot = buffer.readFloat();
        float zRot = buffer.readFloat();
        double heightAboveGround = buffer.readDouble();
        int flags = buffer.readUnsignedByte();
        float power = buffer.readFloat();
        float engineSpeed = buffer.readFloat();
        try {
            return new Entry(entityId, entityType, vehicleId, displayId, position, velocity,
                    xRot, yRot, zRot, heightAboveGround,
                    (flags & FLAG_DESTROYED) != 0,
                    (flags & FLAG_ENGINE_ON) != 0,
                    power, engineSpeed);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid RVP remote vehicle visual entry", exception);
        }
    }

    /** 编码一个双精度三维向量。 */
    private static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x);
        buffer.writeDouble(value.y);
        buffer.writeDouble(value.z);
    }

    /** 解码一个双精度三维向量。 */
    private static Vec3 readVec3(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    /**
     * 单个远距载具的最低视觉状态。
     *
     * @param entityId 服务端实体 ID，用于更新、移除和正常实体互斥
     * @param entityType 实体类型注册表 ID，阶段 B 用于创建正确载具子类
     * @param vehicleId 本体车型数据 ID
     * @param displayId 本体显示变体 ID
     * @param position 服务端世界位置，单位格
     * @param velocity 服务端速度，单位格每 tick
     * @param xRot 俯仰角，单位度
     * @param yRot 偏航角，单位度
     * @param zRot 滚转角，单位度
     * @param heightAboveGround 服务端权威离地高度，单位格
     * @param destroyed 是否已进入损毁状态
     * @param engineOn 发动机是否开启
     * @param power 本体载具动力值
     * @param engineSpeed 本体载具发动机转速表现值
     */
    public record Entry(
            int entityId,
            ResourceLocation entityType,
            ResourceLocation vehicleId,
            ResourceLocation displayId,
            Vec3 position,
            Vec3 velocity,
            float xRot,
            float yRot,
            float zRot,
            double heightAboveGround,
            boolean destroyed,
            boolean engineOn,
            float power,
            float engineSpeed) {
        public Entry {
            if (entityId < 0) {
                throw new IllegalArgumentException("RVP remote vehicle entity ID must be non-negative");
            }
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(vehicleId, "vehicleId");
            Objects.requireNonNull(displayId, "displayId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(velocity, "velocity");
            boolean finite = Double.isFinite(position.x)
                    && Double.isFinite(position.y)
                    && Double.isFinite(position.z)
                    && Double.isFinite(velocity.x)
                    && Double.isFinite(velocity.y)
                    && Double.isFinite(velocity.z)
                    && Float.isFinite(xRot)
                    && Float.isFinite(yRot)
                    && Float.isFinite(zRot)
                    && Double.isFinite(heightAboveGround)
                    && heightAboveGround >= 0.0D
                    && Float.isFinite(power)
                    && Float.isFinite(engineSpeed);
            if (!finite) {
                throw new IllegalArgumentException("RVP remote vehicle entry contains invalid numeric value");
            }
        }
    }
}
