package org.ywzj.rvp.virtualflight.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_GuidancePhase;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_VirtualTrajectoryState;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 单枚虚拟导弹的可持久化服务端权威状态。
 *
 * <p>固定身份和来源字段保持不可变；积分、阶段和恢复计数随 ServerTick 更新。
 * {@link #save()} 与 {@link #load(CompoundTag)} 是唯一 NBT 编解码入口，运行时 Ticket
 * 集合不会写入存档，重启后必须重新经过全局公平预算。</p>
 */
public final class RVP_VirtualMissileState {
    /** 单条状态的 NBT schema 版本；与 SavedData 容器版本分开演进。 */
    static final int STATE_SCHEMA_VERSION = 1;
    /** 原版世界边界内允许持久化的最大水平绝对坐标。 */
    private static final double MAX_WORLD_COORDINATE = 2.9999984E7;

    /** 跨实体重建和服务器重启保持不变的飞行主键。 */
    public final UUID flightUuid;
    /** 所属维度的稳定资源 ID。 */
    public final ResourceLocation dimension;
    /** 当前 schema 的 RVP 武器资源 ID。 */
    public final ResourceLocation weaponId;
    /** 发射者 UUID；实体未加载或不存在时可为空。 */
    @Nullable public final UUID ownerUuid;
    /** 发射载具 UUID；恢复时只在同维度尝试重新绑定。 */
    @Nullable public final UUID shooterVehicleUuid;
    /** 真实导弹首次发射位置，用于连续航程和诊断。 */
    public final Vec3 launchPosition;
    /** 可替换的完整实体运行快照；每次积分按字段语义推进或规范化子系统状态。 */
    public RVP_VirtualMissileSnapshot snapshot;
    /** 转虚拟前的实体 ID，仅用于日志；不可作为恢复主键。 */
    public final int originalEntityId;
    /** 冷发射结束 Tick，用于继续计算发动机点火时间。 */
    public final int coldLaunchTimeTick;
    /** 首次进入虚拟态的世界 gameTime。 */
    public final long enteredGameTime;
    /** 最近一次积分或恢复等待更新的世界 gameTime。 */
    public long lastUpdatedGameTime;
    /** 创建记录时所用的轨迹实现 ID。 */
    public final String trajectoryImplementationId;
    /** 创建记录时所用的轨迹状态版本。 */
    public final int trajectoryStateVersion;
    /** 当前服务端权威状态机阶段。 */
    public RVP_VirtualFlightPhase phase;
    /** 本次虚拟态已经完成的逻辑积分 Tick 数。 */
    public int virtualFlightTicks;
    /** 进入恢复流程后累计等待 Tick。 */
    public int restoreWaitTicks;
    /** 实体重建或 UUID 冲突累计失败次数。 */
    public int restoreAttemptCount;
    /** 首次进入恢复队列的 gameTime，用于稳定公平排序。 */
    public long restoreRequestedGameTime;

    /** 仅运行时使用；重启后从空集合按公平预算重新获得 Ticket。 */
    final Set<Long> grantedRestoreChunks = new LinkedHashSet<>();
    /** 重启加载后的首 Tick 标志，用来冻结停服期间时间。 */
    boolean runtimeInitialized;
    /** 下一次允许执行实体重建的 gameTime；失败重试期间使用。 */
    long nextRestoreAttemptGameTime;

    /**
     * 创建一条完整权威记录。调用者应在加入 SavedData 前执行
     * {@link #isStructurallyValid()}。
     *
     * @param flightUuid 稳定飞行 UUID
     * @param dimension 所属维度资源 ID
     * @param weaponId 当前 schema 武器 ID
     * @param ownerUuid 可选发射者 UUID
     * @param shooterVehicleUuid 可选发射载具 UUID
     * @param launchPosition 初始发射位置
     * @param snapshot 完整实体运行快照
     * @param originalEntityId 转换前实体 ID，仅用于诊断
     * @param coldLaunchTimeTick 冷发射结束 Tick
     * @param enteredGameTime 进入虚拟态的 gameTime
     * @param lastUpdatedGameTime 最近更新时间
     * @param trajectoryImplementationId 轨迹实现 ID
     * @param trajectoryStateVersion 轨迹状态版本
     * @param phase 权威虚拟飞行阶段
     * @param virtualFlightTicks 已积分 Tick
     * @param restoreWaitTicks 已等待恢复 Tick
     * @param restoreAttemptCount 恢复失败次数
     * @param restoreRequestedGameTime 首次请求恢复的 gameTime
     */
    public RVP_VirtualMissileState(
            UUID flightUuid, ResourceLocation dimension, ResourceLocation weaponId,
            @Nullable UUID ownerUuid, @Nullable UUID shooterVehicleUuid, Vec3 launchPosition,
            RVP_VirtualMissileSnapshot snapshot, int originalEntityId, int coldLaunchTimeTick,
            long enteredGameTime, long lastUpdatedGameTime, String trajectoryImplementationId,
            int trajectoryStateVersion, RVP_VirtualFlightPhase phase, int virtualFlightTicks,
            int restoreWaitTicks, int restoreAttemptCount, long restoreRequestedGameTime) {
        this.flightUuid = flightUuid;
        this.dimension = dimension;
        this.weaponId = weaponId;
        this.ownerUuid = ownerUuid;
        this.shooterVehicleUuid = shooterVehicleUuid;
        this.launchPosition = launchPosition;
        this.snapshot = snapshot;
        this.originalEntityId = originalEntityId;
        this.coldLaunchTimeTick = coldLaunchTimeTick;
        this.enteredGameTime = enteredGameTime;
        this.lastUpdatedGameTime = lastUpdatedGameTime;
        this.trajectoryImplementationId = trajectoryImplementationId;
        this.trajectoryStateVersion = trajectoryStateVersion;
        this.phase = phase;
        this.virtualFlightTicks = virtualFlightTicks;
        this.restoreWaitTicks = restoreWaitTicks;
        this.restoreAttemptCount = restoreAttemptCount;
        this.restoreRequestedGameTime = restoreRequestedGameTime;
    }

    /** @return 当前完整快照中的纯轨迹子状态。 */
    public RVP_VirtualTrajectoryState trajectory() { return snapshot.trajectory(); }

    /**
     * 替换纯轨迹子状态，并按第一阶段固定 GPS 准入契约推进其余运行状态。
     *
     * <p>目标点、GPS 散布和发动机绝对时间字段冻结；飞行 Tick/life 由轨迹继续计时；
     * 最近制导点与 GPS 垂直重置按虚拟闭环结果重算；终端制导、雷达、IR 与 Top Attack
     * 均被准入规则禁止，因此恢复为明确的未激活状态，不能把入口时的陈旧记忆写回实体。</p>
     *
     * @param trajectory 积分器返回的新轨迹状态
     */
    public void updateTrajectory(RVP_VirtualTrajectoryState trajectory) {
        snapshot = new RVP_VirtualMissileSnapshot(
                trajectory, snapshot.targetPosition(), snapshot.targetPosition(),
                null, Vec3.ZERO, RVP_GuidancePhase.MAIN,
                snapshot.motorBurnEndTick(),
                snapshot.secondPulseBurnTimeTick(), false, false,
                0, true,
                snapshot.gpsTargetOffset(), snapshot.gpsTargetOffsetResolved(),
                null, null, null, false,
                -1, Integer.MIN_VALUE, false);
    }

    /**
     * 把本条权威记录编码为独立 NBT。
     *
     * @return 包含状态 schema 版本的完整 CompoundTag
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("stateSchemaVersion", STATE_SCHEMA_VERSION);
        tag.putUUID("flightUuid", flightUuid);
        tag.putString("dimension", dimension.toString());
        tag.putString("weaponId", weaponId.toString());
        putUuid(tag, "ownerUuid", ownerUuid);
        putUuid(tag, "shooterVehicleUuid", shooterVehicleUuid);
        tag.put("launchPosition", writeVec3(launchPosition));
        // 调用快照编码器集中保存实体态必须恢复的字段。
        tag.put("snapshot", saveSnapshot(snapshot));
        tag.putInt("originalEntityId", originalEntityId);
        tag.putInt("coldLaunchTimeTick", coldLaunchTimeTick);
        tag.putLong("enteredGameTime", enteredGameTime);
        tag.putLong("lastUpdatedGameTime", lastUpdatedGameTime);
        tag.putString("trajectoryImplementationId", trajectoryImplementationId);
        tag.putInt("trajectoryStateVersion", trajectoryStateVersion);
        tag.putString("phase", phase.name());
        tag.putInt("virtualFlightTicks", virtualFlightTicks);
        tag.putInt("restoreWaitTicks", restoreWaitTicks);
        tag.putInt("restoreAttemptCount", restoreAttemptCount);
        tag.putLong("restoreRequestedGameTime", restoreRequestedGameTime);
        return tag;
    }

    /**
     * 从 NBT 解码并校验一条记录。任何版本、字段、枚举或数值异常都安全返回空。
     *
     * @param tag SavedData 中的一条 missile CompoundTag
     * @return 成功且结构合法的状态
     */
    public static Optional<RVP_VirtualMissileState> load(CompoundTag tag) {
        try {
            if (tag.getInt("stateSchemaVersion") != STATE_SCHEMA_VERSION || !tag.hasUUID("flightUuid")) {
                return Optional.empty();
            }
            ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("dimension"));
            ResourceLocation weaponId = ResourceLocation.tryParse(tag.getString("weaponId"));
            if (dimensionId == null || weaponId == null
                    || !tag.contains("launchPosition", Tag.TAG_COMPOUND)
                    || !tag.contains("snapshot", Tag.TAG_COMPOUND)) {
                return Optional.empty();
            }
            CompoundTag snapshotTag = tag.getCompound("snapshot");
            if (!snapshotTag.contains("position", Tag.TAG_COMPOUND)
                    || !snapshotTag.contains("velocity", Tag.TAG_COMPOUND)
                    || !snapshotTag.contains("targetPosition", Tag.TAG_COMPOUND)
                    || !snapshotTag.contains("remainingLife", Tag.TAG_INT)) return Optional.empty();
            Vec3 launch = readVec3(tag.getCompound("launchPosition"));
            // 基础必需字段确认后才调用完整快照解码器。
            RVP_VirtualMissileSnapshot snapshot = loadSnapshot(snapshotTag);
            RVP_VirtualFlightPhase phase = parseEnum(RVP_VirtualFlightPhase.class, tag.getString("phase"),
                    RVP_VirtualFlightPhase.VIRTUAL_CRUISE);
            RVP_VirtualMissileState state = new RVP_VirtualMissileState(
                    tag.getUUID("flightUuid"), dimensionId, weaponId,
                    getUuid(tag, "ownerUuid"), getUuid(tag, "shooterVehicleUuid"), launch, snapshot,
                    tag.getInt("originalEntityId"), Math.max(tag.getInt("coldLaunchTimeTick"), 0),
                    tag.getLong("enteredGameTime"), tag.getLong("lastUpdatedGameTime"),
                    tag.getString("trajectoryImplementationId"), tag.getInt("trajectoryStateVersion"), phase,
                    Math.max(tag.getInt("virtualFlightTicks"), 0), Math.max(tag.getInt("restoreWaitTicks"), 0),
                    Math.max(tag.getInt("restoreAttemptCount"), 0), tag.getLong("restoreRequestedGameTime"));
            // 最终统一执行有限坐标、寿命、实现 ID 等结构校验。
            return state.isStructurallyValid() ? Optional.of(state) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /**
     * 验证可安全参与积分或实体重建的结构不变量。
     *
     * @return 所有必需 ID、向量、寿命和数值均有效时为 {@code true}
     */
    public boolean isStructurallyValid() {
        RVP_VirtualTrajectoryState trajectory = trajectory();
        return flightUuid != null && dimension != null && weaponId != null
                && trajectoryImplementationId != null && !trajectoryImplementationId.isBlank()
                && validVec(launchPosition) && validVec(trajectory.position()) && validVec(trajectory.velocity())
                && validVec(snapshot.targetPosition()) && validOptionalVec(snapshot.lastGuidancePosition())
                && validVec(snapshot.lastKnownTargetVelocity())
                && validOptionalVec(snapshot.gpsTargetOffset())
                && validOptionalVec(snapshot.topAttackLaunchPosition())
                && validOptionalVec(snapshot.topAttackInitialTargetPosition())
                && validOptionalVec(snapshot.topAttackApexPosition())
                && Double.isFinite(trajectory.peakFlightSpeed())
                && Double.isFinite(trajectory.flightDistance()) && trajectory.remainingLife() >= 0;
    }

    /**
     * 计算恢复中心半径和恢复后首 Tick 终点所需的去重区块集合。
     *
     * @param radius 配置钳制后的区块半径
     */
    Set<ChunkPos> desiredRestoreChunks(int radius) {
        ChunkPos center = new ChunkPos(net.minecraft.core.BlockPos.containing(trajectory().position()));
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) chunks.add(new ChunkPos(x, z));
        }
        chunks.add(new ChunkPos(net.minecraft.core.BlockPos.containing(
                trajectory().position().add(trajectory().velocity()))));
        return chunks;
    }

    /** 编码完整实体运行快照；与 {@link #loadSnapshot(CompoundTag)} 字段一一对应。 */
    private static CompoundTag saveSnapshot(RVP_VirtualMissileSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        RVP_VirtualTrajectoryState t = snapshot.trajectory();
        tag.put("position", writeVec3(t.position()));
        tag.put("velocity", writeVec3(t.velocity()));
        tag.putFloat("xRot", t.xRot());
        tag.putFloat("yRot", t.yRot());
        tag.putDouble("peakFlightSpeed", t.peakFlightSpeed());
        tag.putDouble("flightDistance", t.flightDistance());
        tag.putInt("flightTick", t.flightTick());
        tag.putInt("remainingLife", t.remainingLife());
        tag.putInt("secondPulseStartTick", t.secondPulseStartTick());
        tag.put("targetPosition", writeVec3(snapshot.targetPosition()));
        putOptionalVec(tag, "lastGuidancePosition", snapshot.lastGuidancePosition());
        putUuid(tag, "targetEntityUuid", snapshot.targetEntityUuid());
        tag.put("lastKnownTargetVelocity", writeVec3(snapshot.lastKnownTargetVelocity()));
        tag.putString("guidancePhase", snapshot.guidancePhase().name());
        tag.putInt("motorBurnEndTick", snapshot.motorBurnEndTick());
        tag.putInt("secondPulseBurnTimeTick", snapshot.secondPulseBurnTimeTick());
        tag.putBoolean("activeRadarOn", snapshot.activeRadarOn());
        tag.putBoolean("activeRadarCatch", snapshot.activeRadarCatch());
        tag.putInt("activeRadarLostTargetTick", snapshot.activeRadarLostTargetTick());
        tag.putBoolean("gpsCruiseVerticalResetApplied", snapshot.gpsCruiseVerticalResetApplied());
        putOptionalVec(tag, "gpsTargetOffset", snapshot.gpsTargetOffset());
        tag.putBoolean("gpsTargetOffsetResolved", snapshot.gpsTargetOffsetResolved());
        putOptionalVec(tag, "topAttackLaunchPosition", snapshot.topAttackLaunchPosition());
        putOptionalVec(tag, "topAttackInitialTargetPosition", snapshot.topAttackInitialTargetPosition());
        putOptionalVec(tag, "topAttackApexPosition", snapshot.topAttackApexPosition());
        tag.putBoolean("topAttackApexReached", snapshot.topAttackApexReached());
        tag.putInt("topAttackTriggerTick", snapshot.topAttackTriggerTick());
        tag.putInt("irSeekerGraceUntilTick", snapshot.irSeekerGraceUntilTick());
        tag.putBoolean("irSeekerLossGraceStarted", snapshot.irSeekerLossGraceStarted());
        return tag;
    }

    /** 解码完整实体运行快照；可选字段缺失时使用明确安全默认值。 */
    private static RVP_VirtualMissileSnapshot loadSnapshot(CompoundTag tag) {
        RVP_VirtualTrajectoryState trajectory = new RVP_VirtualTrajectoryState(
                readVec3(tag.getCompound("position")), readVec3(tag.getCompound("velocity")),
                tag.getFloat("xRot"), tag.getFloat("yRot"), tag.getDouble("peakFlightSpeed"),
                tag.getDouble("flightDistance"), tag.getInt("flightTick"), tag.getInt("remainingLife"),
                tag.getInt("secondPulseStartTick"));
        return new RVP_VirtualMissileSnapshot(
                trajectory, readVec3(tag.getCompound("targetPosition")),
                readOptionalVec(tag, "lastGuidancePosition"), getUuid(tag, "targetEntityUuid"),
                tag.contains("lastKnownTargetVelocity", Tag.TAG_COMPOUND)
                        ? readVec3(tag.getCompound("lastKnownTargetVelocity")) : Vec3.ZERO,
                parseEnum(RVP_GuidancePhase.class, tag.getString("guidancePhase"), RVP_GuidancePhase.MAIN),
                tag.getInt("motorBurnEndTick"), tag.getInt("secondPulseBurnTimeTick"),
                tag.getBoolean("activeRadarOn"), tag.getBoolean("activeRadarCatch"),
                tag.getInt("activeRadarLostTargetTick"), tag.getBoolean("gpsCruiseVerticalResetApplied"),
                readOptionalVec(tag, "gpsTargetOffset"), tag.getBoolean("gpsTargetOffsetResolved"),
                readOptionalVec(tag, "topAttackLaunchPosition"),
                readOptionalVec(tag, "topAttackInitialTargetPosition"),
                readOptionalVec(tag, "topAttackApexPosition"), tag.getBoolean("topAttackApexReached"),
                tag.getInt("topAttackTriggerTick"), tag.getInt("irSeekerGraceUntilTick"),
                tag.getBoolean("irSeekerLossGraceStarted"));
    }

    /** 将 Vec3 编码为 x/y/z double 子标签。 */
    private static CompoundTag writeVec3(Vec3 value) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", value.x); tag.putDouble("y", value.y); tag.putDouble("z", value.z);
        return tag;
    }
    /** 从 x/y/z double 子标签解码 Vec3；完整性由上层结构校验负责。 */
    private static Vec3 readVec3(CompoundTag tag) { return new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")); }
    /** 非空时写入可选 Vec3。 */
    private static void putOptionalVec(CompoundTag tag, String key, @Nullable Vec3 value) { if (value != null) tag.put(key, writeVec3(value)); }
    /** 读取可选 Vec3；不存在或类型错误时返回 null。 */
    @Nullable private static Vec3 readOptionalVec(CompoundTag tag, String key) { return tag.contains(key, Tag.TAG_COMPOUND) ? readVec3(tag.getCompound(key)) : null; }
    /** 非空时写入可选 UUID。 */
    private static void putUuid(CompoundTag tag, String key, @Nullable UUID value) { if (value != null) tag.putUUID(key, value); }
    /** 读取可选 UUID；标签不存在时返回 null。 */
    @Nullable private static UUID getUuid(CompoundTag tag, String key) { return tag.hasUUID(key) ? tag.getUUID(key) : null; }
    /** 验证允许为 null 的向量。 */
    private static boolean validOptionalVec(@Nullable Vec3 value) { return value == null || validVec(value); }
    /** 验证向量有限且水平坐标未越过世界安全边界。 */
    private static boolean validVec(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                && Math.abs(value.x) <= MAX_WORLD_COORDINATE && Math.abs(value.z) <= MAX_WORLD_COORDINATE;
    }
    /** 解析稳定枚举名，未知值回退而不让存档加载抛异常。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException ignored) { return fallback; }
    }
}
