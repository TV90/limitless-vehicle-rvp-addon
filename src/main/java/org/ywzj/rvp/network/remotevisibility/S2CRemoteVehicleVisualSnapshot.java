package org.ywzj.rvp.network.remotevisibility;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleBillboardSource;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleSnapshotWarmupMode;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 服务端到客户端的远距载具视觉完整集合快照。
 *
 * @param dimension 快照所属维度
 * @param serverGameTime 服务端生成快照时的世界时间，单位 tick
 * @param sequence 当前玩家连接内单调递增且非负的快照序号
 * @param aggressiveLodBillboard 是否把没有有效 LOD 的目标改为 Billboard
 * @param forceAllVehicleBillboard 是否强制所有目标使用 Billboard
 * @param billboardSource Billboard 图像来源
 * @param dynamicSnapshotWarmupMode 动态快照预热期间的显示方式
 * @param minHeightAboveGroundWithoutDH 未开启 DH 时的最低离地高度，单位米（格）；默认 25，-1 禁用限制
 * @param entries 当前服务端授权的完整载具视觉条目集合
 */
public record S2CRemoteVehicleVisualSnapshot(
        ResourceLocation dimension,
        long serverGameTime,
        long sequence,
        boolean aggressiveLodBillboard,
        boolean forceAllVehicleBillboard,
        RemoteVehicleBillboardSource billboardSource,
        RemoteVehicleSnapshotWarmupMode dynamicSnapshotWarmupMode,
        int minHeightAboveGroundWithoutDH,
        List<Entry> entries) {
    /** 单份载具视觉快照允许的最大条目数。 */
    public static final int MAX_ENTRIES = 1024;
    /** 单辆载具允许同步的最大旋转部件数。 */
    public static final int MAX_ROTATABLE_PARTS = 128;
    /** 单辆载具允许同步的最大开关部件数。 */
    public static final int MAX_SWITCHABLE_PARTS = 128;
    /** 单辆载具允许同步的最大发射架规则数。 */
    public static final int MAX_LAUNCHER_STATES = 16;
    /** 条目损毁状态位。 */
    private static final int FLAG_DESTROYED = 1;
    /** 条目发动机开启状态位。 */
    private static final int FLAG_ENGINE_ON = 1 << 1;

    public S2CRemoteVehicleVisualSnapshot {
        Objects.requireNonNull(dimension, "dimension");
        if (sequence < 0L) {
            throw new IllegalArgumentException("RVP remote vehicle sequence must be non-negative");
        }
        Objects.requireNonNull(billboardSource, "billboardSource");
        Objects.requireNonNull(dynamicSnapshotWarmupMode, "dynamicSnapshotWarmupMode");
        if (minHeightAboveGroundWithoutDH < -1) {
            throw new IllegalArgumentException("RVP remote vehicle minimum height must be -1 or non-negative");
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
        buffer.writeBoolean(message.aggressiveLodBillboard);
        buffer.writeBoolean(message.forceAllVehicleBillboard);
        buffer.writeEnum(message.billboardSource);
        buffer.writeEnum(message.dynamicSnapshotWarmupMode);
        buffer.writeInt(message.minHeightAboveGroundWithoutDH);
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
            boolean aggressiveLodBillboard = buffer.readBoolean();
            boolean forceAllVehicleBillboard = buffer.readBoolean();
            RemoteVehicleBillboardSource billboardSource = buffer.readEnum(RemoteVehicleBillboardSource.class);
            RemoteVehicleSnapshotWarmupMode dynamicSnapshotWarmupMode =
                    buffer.readEnum(RemoteVehicleSnapshotWarmupMode.class);
            int minHeightAboveGroundWithoutDH = buffer.readInt();
            int entryCount = buffer.readVarInt();
            if (entryCount < 0 || entryCount > MAX_ENTRIES) {
                throw new DecoderException("RVP remote vehicle entry count exceeds 1024");
            }
            List<Entry> entries = new java.util.ArrayList<>(entryCount);
            for (int index = 0; index < entryCount; index++) {
                entries.add(readEntry(buffer));
            }
            return new S2CRemoteVehicleVisualSnapshot(
                    dimension, serverGameTime, sequence,
                    aggressiveLodBillboard, forceAllVehicleBillboard,
                    billboardSource, dynamicSnapshotWarmupMode, minHeightAboveGroundWithoutDH, entries);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Invalid RVP remote vehicle visual snapshot", exception);
        }
    }

    /** 通过公共端口分发快照；物理客户端启动时安装真实状态消费者，未安装时保持 NOOP。 */
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
        writeRotatableParts(buffer, entry.rotatableParts);
        writeSwitchableParts(buffer, entry.switchableParts);
        writeLauncherStates(buffer, entry.launcherStates);
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
        List<RotatablePartState> rotatableParts = readRotatableParts(buffer);
        List<SwitchablePartState> switchableParts = readSwitchableParts(buffer);
        List<LauncherDeployVisualState> launcherStates = readLauncherStates(buffer);
        try {
            return new Entry(entityId, entityType, vehicleId, displayId, position, velocity,
                    xRot, yRot, zRot, heightAboveGround,
                    (flags & FLAG_DESTROYED) != 0,
                    (flags & FLAG_ENGINE_ON) != 0,
                    power, engineSpeed, rotatableParts, switchableParts, launcherStates);
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

    /** 写入一辆载具的旋转部件完整集合。 */
    private static void writeRotatableParts(FriendlyByteBuf buffer, List<RotatablePartState> states) {
        buffer.writeVarInt(states.size());
        for (RotatablePartState state : states) {
            buffer.writeVarInt(state.partIndex);
            buffer.writeFloat(state.xRot);
            buffer.writeFloat(state.yRot);
        }
    }

    /** 读取并校验一辆载具的旋转部件完整集合。 */
    private static List<RotatablePartState> readRotatableParts(FriendlyByteBuf buffer) {
        int count = readBoundedCount(buffer, MAX_ROTATABLE_PARTS, "rotatable part");
        List<RotatablePartState> states = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            states.add(new RotatablePartState(buffer.readVarInt(), buffer.readFloat(), buffer.readFloat()));
        }
        return states;
    }

    /** 写入一辆载具的开关部件完整集合。 */
    private static void writeSwitchableParts(FriendlyByteBuf buffer, List<SwitchablePartState> states) {
        buffer.writeVarInt(states.size());
        for (SwitchablePartState state : states) {
            buffer.writeVarInt(state.partIndex);
            buffer.writeEnum(state.kind);
            buffer.writeBoolean(state.on);
        }
    }

    /** 读取并校验一辆载具的开关部件完整集合。 */
    private static List<SwitchablePartState> readSwitchableParts(FriendlyByteBuf buffer) {
        int count = readBoundedCount(buffer, MAX_SWITCHABLE_PARTS, "switchable part");
        List<SwitchablePartState> states = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            states.add(new SwitchablePartState(
                    buffer.readVarInt(), buffer.readEnum(SwitchablePartKind.class), buffer.readBoolean()));
        }
        return states;
    }

    /** 写入一辆载具的 RVP 发射架运行状态完整集合。 */
    private static void writeLauncherStates(FriendlyByteBuf buffer, List<LauncherDeployVisualState> states) {
        buffer.writeVarInt(states.size());
        for (LauncherDeployVisualState state : states) {
            buffer.writeUtf(state.ruleId, 128);
            buffer.writeVarInt(state.switchPartIndex + 1);
            buffer.writeVarInt(state.pitchPartIndex + 1);
            buffer.writeEnum(state.phase);
            buffer.writeVarInt(state.progressTick);
            buffer.writeFloat(state.currentPitch);
            buffer.writeDouble(state.speedKph);
        }
    }

    /** 读取并校验一辆载具的 RVP 发射架运行状态完整集合。 */
    private static List<LauncherDeployVisualState> readLauncherStates(FriendlyByteBuf buffer) {
        int count = readBoundedCount(buffer, MAX_LAUNCHER_STATES, "launcher state");
        List<LauncherDeployVisualState> states = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            states.add(new LauncherDeployVisualState(
                    buffer.readUtf(128),
                    buffer.readVarInt() - 1,
                    buffer.readVarInt() - 1,
                    buffer.readEnum(LauncherDeployPhase.class),
                    buffer.readVarInt(),
                    buffer.readFloat(),
                    buffer.readDouble()));
        }
        return states;
    }

    /** 读取受硬上限保护的嵌套集合长度。 */
    private static int readBoundedCount(FriendlyByteBuf buffer, int maximum, String label) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new DecoderException("RVP remote vehicle " + label + " count exceeds " + maximum);
        }
        return count;
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
     * @param rotatableParts 炮塔、炮管、武器站及发射架俯仰部件的完整旋转状态
     * @param switchableParts 起落架、舱门、武器舱及发射架开关部件的完整状态
     * @param launcherStates RVP 发射架状态机的完整视觉状态
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
            float engineSpeed,
            List<RotatablePartState> rotatableParts,
            List<SwitchablePartState> switchableParts,
            List<LauncherDeployVisualState> launcherStates) {
        public Entry {
            if (entityId < 0) {
                throw new IllegalArgumentException("RVP remote vehicle entity ID must be non-negative");
            }
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(vehicleId, "vehicleId");
            Objects.requireNonNull(displayId, "displayId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(rotatableParts, "rotatableParts");
            Objects.requireNonNull(switchableParts, "switchableParts");
            Objects.requireNonNull(launcherStates, "launcherStates");
            if (rotatableParts.size() > MAX_ROTATABLE_PARTS
                    || switchableParts.size() > MAX_SWITCHABLE_PARTS
                    || launcherStates.size() > MAX_LAUNCHER_STATES) {
                throw new IllegalArgumentException("RVP remote vehicle nested visual state count exceeds limit");
            }
            rotatableParts = List.copyOf(rotatableParts);
            switchableParts = List.copyOf(switchableParts);
            launcherStates = List.copyOf(launcherStates);
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

        /** 兼容只构造既有整车字段的内部测试与调用点。 */
        public Entry(int entityId, ResourceLocation entityType, ResourceLocation vehicleId,
                     ResourceLocation displayId, Vec3 position, Vec3 velocity,
                     float xRot, float yRot, float zRot, double heightAboveGround,
                     boolean destroyed, boolean engineOn, float power, float engineSpeed) {
            this(entityId, entityType, vehicleId, displayId, position, velocity,
                    xRot, yRot, zRot, heightAboveGround, destroyed, engineOn, power, engineSpeed,
                    List.of(), List.of(), List.of());
        }
    }

    /**
     * 单个可旋转部件的服务端权威姿态。
     *
     * @param partIndex 车型部件列表索引
     * @param xRot 部件局部俯仰角，单位度
     * @param yRot 部件局部偏航角，单位度
     */
    public record RotatablePartState(int partIndex, float xRot, float yRot) {
        public RotatablePartState {
            if (partIndex < 0 || !Float.isFinite(xRot) || !Float.isFinite(yRot)) {
                throw new IllegalArgumentException("Invalid RVP remote rotatable part state");
            }
        }
    }

    /** 远距视觉需要区分的开关部件类型。 */
    public enum SwitchablePartKind {
        /** 起落架。 */
        LANDING_GEAR,
        /** 乘员舱门。 */
        DOOR,
        /** 武器舱门。 */
        WEAPON_BAY,
        /** RVP TEL 或其他发射架开关部件。 */
        LAUNCHER
    }

    /**
     * 单个开关部件的服务端权威终态。
     *
     * @param partIndex 车型部件列表索引
     * @param kind 部件视觉类型
     * @param on 本体开关状态
     */
    public record SwitchablePartState(int partIndex, SwitchablePartKind kind, boolean on) {
        public SwitchablePartState {
            if (partIndex < 0) {
                throw new IllegalArgumentException("Invalid RVP remote switchable part index");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** RVP 发射架部署阶段的网络稳定枚举。 */
    public enum LauncherDeployPhase {
        /** 完全收拢。 */
        CLOSED,
        /** 正在展开。 */
        DEPLOYING,
        /** 完全展开。 */
        OPEN,
        /** 正在收回。 */
        RETRACTING
    }

    /**
     * 单条 RVP 发射架规则的服务端权威状态。
     *
     * @param ruleId 发射架配置规则 ID
     * @param switchPartIndex 开关部件索引；不存在时为 -1
     * @param pitchPartIndex 俯仰部件索引；不存在时为 -1
     * @param phase 部署阶段
     * @param progressTick 当前阶段进度，单位 Tick
     * @param currentPitch 当前权威俯仰角，单位度
     * @param speedKph 状态机采样时的载具速度，单位千米每小时
     */
    public record LauncherDeployVisualState(
            String ruleId,
            int switchPartIndex,
            int pitchPartIndex,
            LauncherDeployPhase phase,
            int progressTick,
            float currentPitch,
            double speedKph) {
        public LauncherDeployVisualState {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(phase, "phase");
            if (ruleId.isBlank() || ruleId.length() > 128
                    || switchPartIndex < -1 || pitchPartIndex < -1
                    || progressTick < 0
                    || !Float.isFinite(currentPitch) || !Double.isFinite(speedKph)) {
                throw new IllegalArgumentException("Invalid RVP remote launcher visual state");
            }
        }
    }
}
