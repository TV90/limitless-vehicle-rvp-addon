package org.ywzj.rvp.network.remotevisibility;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 虚拟弹的独立视觉完整集合；不携带制导、伤害或目标授权。
 * @param dimension 当前观察者所属维度
 * @param sessionId 本次服务端运行身份，不写入存档
 * @param sequence 对该观察者递增的消息序号，同一世界刻也可递增
 * @param serverGameTime 已完成计算的服务端世界刻，只用于样本间隔
 * @param entries 本次允许保留的全部显示对象，空集合表示撤销
 * @param departures 离开上一集合的身份及原因，恢复时附实际真实弹位置
 */
public record S2CVirtualMissileVisualSnapshot(ResourceLocation dimension, UUID sessionId, long sequence,
        long serverGameTime, List<Entry> entries, List<Departure> departures) {
    /** 每个完整集合最多显示的弹数。 */
    public static final int MAX_ENTRIES = 128;
    /** 相对上一完整集合的离开项上限。 */
    public static final int MAX_DEPARTURES = 128;
    /** 单消息编码后的最大字节数，不含 Forge 消息编号。 */
    public static final int MAX_PACKET_BYTES = 65_536;
    /** 资源名称最大字符数；ResourceLocation 本身只允许 ASCII 字符。 */
    public static final int MAX_RESOURCE_LENGTH = 256;
    /** 当前燃烧窗口及尾烟保持期同步上限，单位飞行刻。 */
    public static final int MAX_BURN_TICKS = 1_200;

    /** 发动机实际阶段；边界剩余 0 刻仍可处于燃烧状态。 */
    public enum MotorPhase {
        /** 未点火、脉冲间隙或已燃尽。 */ IDLE,
        /** 一级发动机燃烧。 */ PRIMARY,
        /** 二脉冲发动机燃烧。 */ SECOND_PULSE
    }

    /** 离开视觉集合不等于死亡，恢复须明确交接给真实来源。 */
    public enum DepartureReason {
        /** 已成功恢复真实弹。 */ RESTORED,
        /** 权威虚拟记录已经终止。 */ TERMINATED,
        /** 失去范围、授权或本轮预算。 */ OUT_OF_SCOPE
    }

    /**
     * 一枚导弹的不可变显示事实。
     * @param flightUuid 稳定飞行身份
     * @param entityId 本次会话沿用的实体编号，仅配对和尾烟使用
     * @param weaponId 当前武器资源，用于配置和 display 查找
     * @param position 已完成计算的位置，单位格
     * @param velocity 速度，单位格/飞行刻；暂停阶段不外推
     * @param yaw 机头偏航，单位度
     * @param pitch 机头俯仰，单位度，不用速度覆盖
     * @param flightTick 已完成的飞行年龄，单位刻
     * @param phase 权威虚拟阶段，恢复请求和等待均视为暂停
     * @param motorPhase 权威发动机当前阶段
     * @param motorBurnRemainingTicks 当前燃烧窗口的剩余飞行刻，不含未来点火等待
     * @param trailHoldRemainingTicks 距最晚燃尽的剩余刻，仅用于现有尾烟保持期
     * @param guidancePhase 当前主段/末段制导阶段，仅显示和操作提示使用，不执行客户端制导
     */
    public record Entry(UUID flightUuid, int entityId, ResourceLocation weaponId, Vec3 position, Vec3 velocity,
            float yaw, float pitch, int flightTick, RVP_VirtualFlightPhase phase, MotorPhase motorPhase,
            int motorBurnRemainingTicks, int trailHoldRemainingTicks, org.ywzj.rvp.guidance.RVP_GuidancePhase guidancePhase) {
        /** 常规主段样本的便捷构造器，实际网络始终编码完整阶段字段。 */
        public Entry(UUID flightUuid, int entityId, ResourceLocation weaponId, Vec3 position, Vec3 velocity,
                float yaw, float pitch, int flightTick, RVP_VirtualFlightPhase phase, MotorPhase motorPhase,
                int motorBurnRemainingTicks, int trailHoldRemainingTicks) {
            this(flightUuid, entityId, weaponId, position, velocity, yaw, pitch, flightTick, phase, motorPhase,
                    motorBurnRemainingTicks, trailHoldRemainingTicks, org.ywzj.rvp.guidance.RVP_GuidancePhase.MAIN);
        }
        public Entry {
            Objects.requireNonNull(flightUuid, "flightUuid");
            requireResource(weaponId);
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(motorPhase, "motorPhase");
            Objects.requireNonNull(guidancePhase, "guidancePhase");
            if (entityId < 0 || flightTick < 0 || !finite(position) || !finite(velocity)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || motorBurnRemainingTicks < 0 || motorBurnRemainingTicks > MAX_BURN_TICKS
                    || trailHoldRemainingTicks < 0 || trailHoldRemainingTicks > MAX_BURN_TICKS
                    || motorPhase == MotorPhase.IDLE && motorBurnRemainingTicks != 0) {
                throw new IllegalArgumentException("Invalid virtual missile visual entry");
            }
        }
    }

    /**
     * @param flightUuid 离开的稳定身份
     * @param entityId 离开的会话编号
     * @param reason 离开原因
     * @param finalSample 仅 RESTORED 必填，从已入世真实弹读取的最终显示样本
     */
    public record Departure(UUID flightUuid, int entityId, DepartureReason reason, Entry finalSample) {
        public Departure {
            Objects.requireNonNull(flightUuid, "flightUuid");
            Objects.requireNonNull(reason, "reason");
            if (entityId < 0 || (reason == DepartureReason.RESTORED) != (finalSample != null)
                    || finalSample != null && (!flightUuid.equals(finalSample.flightUuid())
                    || entityId != finalSample.entityId())) {
                throw new IllegalArgumentException("Invalid virtual missile visual departure");
            }
        }
    }

    public S2CVirtualMissileVisualSnapshot {
        requireResource(dimension);
        Objects.requireNonNull(sessionId, "sessionId");
        if (sequence < 0 || serverGameTime < 0 || entries.size() > MAX_ENTRIES
                || departures.size() > MAX_DEPARTURES) throw new IllegalArgumentException("Visual snapshot limit");
        entries = List.copyOf(entries);
        departures = List.copyOf(departures);
        Set<UUID> uuids = new HashSet<>();
        Set<Integer> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (!uuids.add(entry.flightUuid()) || !ids.add(entry.entityId()))
                throw new IllegalArgumentException("Duplicate visual identity");
        }
        Set<UUID> departed = new HashSet<>();
        for (Departure departure : departures) {
            if (uuids.contains(departure.flightUuid()) || !departed.add(departure.flightUuid()))
                throw new IllegalArgumentException("Duplicate visual departure");
        }
        if (encodedSize(dimension, entries, departures) > MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Visual snapshot byte budget");
    }

    /** 编码前已由不可变构造器检查字段、数量与实际字节预算。 */
    public static void encode(S2CVirtualMissileVisualSnapshot message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.dimension.toString(), MAX_RESOURCE_LENGTH);
        buffer.writeUUID(message.sessionId);
        buffer.writeLong(message.sequence);
        buffer.writeLong(message.serverGameTime);
        buffer.writeVarInt(message.entries.size());
        for (Entry entry : message.entries) writeEntry(buffer, entry);
        buffer.writeVarInt(message.departures.size());
        for (Departure departure : message.departures) {
            buffer.writeUUID(departure.flightUuid());
            buffer.writeVarInt(departure.entityId());
            buffer.writeByte(departure.reason().ordinal());
            if (departure.finalSample() != null) writeEntry(buffer, departure.finalSample());
        }
    }

    /** 先限制整包和集合数量，再逐字段读取；坏枚举不会经 readEnum 越界访问数组。 */
    public static S2CVirtualMissileVisualSnapshot decode(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_PACKET_BYTES) throw new DecoderException("Visual packet too large");
        try {
            ResourceLocation dimension = readResource(buffer);
            UUID session = buffer.readUUID();
            long sequence = buffer.readLong();
            long gameTime = buffer.readLong();
            int count = readCount(buffer, MAX_ENTRIES);
            var entries = new java.util.ArrayList<Entry>(count);
            for (int index = 0; index < count; index++) entries.add(readEntry(buffer));
            int removedCount = readCount(buffer, MAX_DEPARTURES);
            var departures = new java.util.ArrayList<Departure>(removedCount);
            for (int index = 0; index < removedCount; index++) {
                UUID uuid = buffer.readUUID();
                int id = buffer.readVarInt();
                DepartureReason reason = readEnum(buffer, DepartureReason.values());
                departures.add(new Departure(uuid, id, reason,
                        reason == DepartureReason.RESTORED ? readEntry(buffer) : null));
            }
            if (buffer.isReadable()) throw new DecoderException("Trailing visual data");
            return new S2CVirtualMissileVisualSnapshot(dimension, session, sequence, gameTime, entries, departures);
        } catch (IllegalArgumentException | NullPointerException | IndexOutOfBoundsException exception) {
            throw new DecoderException("Invalid virtual missile visual snapshot", exception);
        }
    }

    /** 主线程接收器经公共空端口分派，专服注册协议时不会解析客户端类型。 */
    public static void handle(S2CVirtualMissileVisualSnapshot message, Supplier<NetworkEvent.Context> supplier) {
        // 调用独立视觉端口；RVP_Network 的 consumerMainThread 已保证在游戏主线程执行。
        RVP_VirtualMissileVisualEndpoint.accept(message);
        supplier.get().setPacketHandled(true);
    }

    /** 精确计算此协议编码大小，服务端预算和测试共用，不分配临时网络缓冲区。 */
    public static int encodedSize(ResourceLocation dimension, List<Entry> entries, List<Departure> departures) {
        int size = resourceSize(dimension) + 32 + FriendlyByteBuf.getVarIntSize(entries.size())
                + FriendlyByteBuf.getVarIntSize(departures.size());
        for (Entry entry : entries) size += entrySize(entry);
        for (Departure departure : departures) size += 17 + FriendlyByteBuf.getVarIntSize(departure.entityId())
                + (departure.finalSample() == null ? 0 : entrySize(departure.finalSample()));
        return size;
    }

    /** 写入固定显示字段，不使用 NBT 或任意实体类型。 */
    private static void writeEntry(FriendlyByteBuf buffer, Entry entry) {
        buffer.writeUUID(entry.flightUuid());
        buffer.writeVarInt(entry.entityId());
        buffer.writeUtf(entry.weaponId().toString(), MAX_RESOURCE_LENGTH);
        writeVector(buffer, entry.position());
        writeVector(buffer, entry.velocity());
        buffer.writeFloat(entry.yaw());
        buffer.writeFloat(entry.pitch());
        buffer.writeVarInt(entry.flightTick());
        buffer.writeByte(entry.phase().ordinal());
        buffer.writeByte(entry.motorPhase().ordinal());
        buffer.writeVarInt(entry.motorBurnRemainingTicks());
        buffer.writeVarInt(entry.trailHoldRemainingTicks());
        buffer.writeByte(entry.guidancePhase().ordinal());
    }

    /** 逐项构造时执行同编码端一致的数值校验。 */
    private static Entry readEntry(FriendlyByteBuf buffer) {
        return new Entry(buffer.readUUID(), buffer.readVarInt(), readResource(buffer), readVector(buffer),
                readVector(buffer), buffer.readFloat(), buffer.readFloat(), buffer.readVarInt(),
                readEnum(buffer, RVP_VirtualFlightPhase.values()), readEnum(buffer, MotorPhase.values()),
                buffer.readVarInt(), buffer.readVarInt(), readEnum(buffer, org.ywzj.rvp.guidance.RVP_GuidancePhase.values()));
    }

    /** 单条记录精确大小；向量共 48 字节，角度 8 字节，飞行、发动机、制导阶段各 1 字节。 */
    private static int entrySize(Entry entry) {
        return 75 + FriendlyByteBuf.getVarIntSize(entry.entityId()) + resourceSize(entry.weaponId())
                + FriendlyByteBuf.getVarIntSize(entry.flightTick())
                + FriendlyByteBuf.getVarIntSize(entry.motorBurnRemainingTicks())
                + FriendlyByteBuf.getVarIntSize(entry.trailHoldRemainingTicks());
    }

    /** UTF-8 长度前缀也必须计入实际预算。 */
    private static int resourceSize(ResourceLocation resource) {
        int length = resource.toString().getBytes(StandardCharsets.UTF_8).length;
        return FriendlyByteBuf.getVarIntSize(length) + length;
    }

    /** 资源名长度在编码前限制，避免服务端发送无法解码的名称。 */
    private static void requireResource(ResourceLocation resource) {
        if (Objects.requireNonNull(resource, "resource").toString().length() > MAX_RESOURCE_LENGTH)
            throw new IllegalArgumentException("Visual resource name too long");
    }

    /** 只分配有上限的字符串。 */
    private static ResourceLocation readResource(FriendlyByteBuf buffer) {
        return ResourceLocation.tryParse(buffer.readUtf(MAX_RESOURCE_LENGTH));
    }

    /** 防止负数或超量计数触发无界集合分配。 */
    private static int readCount(FriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new DecoderException("Visual collection limit");
        return count;
    }

    /** 枚举固定为单字节，读取前检查范围。 */
    private static <T> T readEnum(FriendlyByteBuf buffer, T[] values) {
        int value = buffer.readUnsignedByte();
        if (value >= values.length) throw new DecoderException("Invalid visual enum");
        return values[value];
    }

    /** 保留双精度世界坐标。 */
    private static void writeVector(FriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x); buffer.writeDouble(value.y); buffer.writeDouble(value.z);
    }

    /** 统一向量读取顺序。 */
    private static Vec3 readVector(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    /** 网络数值必须有限，NaN 和 Infinity 不能流入渲染矩阵。 */
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
