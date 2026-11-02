package org.ywzj.rvp.uav;

import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无人机自动盘旋状态管理器。按 UAV 实体 UUID 索引盘旋状态。
 * <p>非 Mixin，纯静态管理器，遵循 rvp-avoid-mixin 原则。</p>
 */
public final class RVP_UavLoiterManager {

    private RVP_UavLoiterManager() {}

    /** 盘旋仅用于固定翼；旋翼部署实例由独立悬停逻辑管理，地面子车不接入飞控。 */
    public static boolean supportsLoiter(AbstractVehicle vehicle) {
        return vehicle instanceof FixedWingVehicle;
    }

    /** 盘旋阶段。 */
    public enum LoiterPhase {
        /** 爬升到安全高度。 */
        CLIMB,
        /** 朝盘旋圆周直飞。 */
        TRANSIT,
        /** 接近圆周，减速对齐切线。 */
        APPROACH,
        /** 圆周盘旋。 */
        LOITER,
        /** 低速/掉高/过坡恢复，条件恢复后回到圆周捕获。 */
        RECOVERY
    }

    /** 对外不可变快照。 */
    public record LoiterState(
            boolean active,
            boolean signalPaused,
            LoiterPhase phase,
            double centerX, double centerY, double centerZ,
            double radius,
            double requestedRadius,
            double altitude,
            @Nullable UUID followParentUuid,
            boolean markedCenter,
            int phaseTickCounter,
            int phaseTimeout
    ) {}

    /** 内部可变状态。 */
    static final class MutableState {
        volatile boolean active = false;
        volatile boolean planInitialized = false;
        volatile boolean signalPaused = false;
        volatile LoiterPhase phase = LoiterPhase.CLIMB;
        volatile double centerX, centerY, centerZ;
        volatile double radius = 120;
        volatile double effectiveRadius = 120;
        volatile double altitude = 80;
        @Nullable volatile UUID followParentUuid = null;
        volatile boolean markedCenter = false;
        volatile int phaseTickCounter = 0;
        volatile int phaseTimeout = 200;

        LoiterState snapshot() {
            return new LoiterState(
                    active, signalPaused, phase, centerX, centerY, centerZ, effectiveRadius, radius, altitude,
                    followParentUuid, markedCenter,
                    phaseTickCounter, phaseTimeout);
        }
    }

    private static final Map<UUID, MutableState> STATES = new ConcurrentHashMap<>();

    /** 激活盘旋（跟随母车模式）。 */
    public static void enableFollowParent(UUID uavUuid, UUID parentUuid,
                                          double radius, double altitudeOffset,
                                          double parentX, double parentY, double parentZ) {
        MutableState s = STATES.computeIfAbsent(uavUuid, k -> new MutableState());
        if (s.planInitialized) {
            // 控制权切换/重连不重置巡航阶段或显式标点；跟随中心由 tick 同步。
            s.active = true;
            s.signalPaused = false;
            return;
        }
        s.planInitialized = true;
        s.active = true;
        s.signalPaused = false;
        s.phase = LoiterPhase.CLIMB;
        s.followParentUuid = parentUuid;
        s.markedCenter = false;
        s.radius = radius;
        s.effectiveRadius = radius;
        s.centerY = parentY;
        s.centerX = parentX;
        s.centerZ = parentZ;
        s.altitude = parentY + altitudeOffset;
        s.phaseTickCounter = 0;
        s.phaseTimeout = 200;
    }

    /** 激活盘旋（固定圆心模式，战术地图标记）。 */
    public static void enableMarkedCenter(UUID uavUuid, Vec3 center,
                                          double radius, double altitude) {
        MutableState s = STATES.computeIfAbsent(uavUuid, k -> new MutableState());
        s.planInitialized = true;
        s.active = true;
        s.phase = LoiterPhase.CLIMB;
        s.followParentUuid = null;
        s.markedCenter = true;
        s.centerX = center.x;
        s.centerY = center.y;
        s.centerZ = center.z;
        s.radius = radius;
        s.effectiveRadius = radius;
        s.altitude = altitude;
        s.phaseTickCounter = 0;
        s.phaseTimeout = 200;
    }

    /** 更新圆心（战术地图重新标记时）。 */
    public static void updateCenter(UUID uavUuid, Vec3 newCenter, double altitude) {
        MutableState s = STATES.get(uavUuid);
        if (s == null || !s.active) {
            return;
        }
        s.centerX = newCenter.x;
        s.centerY = newCenter.y;
        s.centerZ = newCenter.z;
        s.altitude = altitude;
        s.markedCenter = true;
        s.followParentUuid = null;
        s.phase = LoiterPhase.APPROACH;
        s.phaseTickCounter = 0;
        s.phaseTimeout = 400;
    }

    /** 关闭盘旋。 */
    public static void disable(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        if (s != null) {
            s.active = false;
            s.signalPaused = false;
        }
    }

    /** 重新打开已保存计划而不覆盖圆心、半径意图或跟随/标记模式。 */
    public static boolean resumeExistingPlan(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        if (s == null || !s.planInitialized) return false;
        s.active = true;
        s.signalPaused = false;
        if (s.phase == LoiterPhase.LOITER) {
            s.phase = LoiterPhase.APPROACH;
            s.phaseTickCounter = 0;
        }
        return true;
    }

    /** 获取状态快照。 */
    @Nullable
    public static LoiterState get(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        return s != null ? s.snapshot() : null;
    }

    /** 获取所有 active 状态（tick 遍历用）。返回 UUID → 内部可变状态的 entry 集。 */
    public static Map<UUID, MutableState> getAllInternal() {
        return STATES;
    }

    /** 移除条目（UAV 被移除时）。 */
    public static void remove(UUID uavUuid) {
        STATES.remove(uavUuid);
    }

    /** 是否处于盘旋状态。 */
    public static boolean isLoitering(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        return s != null && s.active && !s.signalPaused;
    }

    /** 活动巡航拥有运动控制权；信号暂停时仍应屏蔽人工运动包。 */
    public static boolean isControlLocked(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        return s != null && s.active;
    }

    /** 因信号失联暂停；保留用户巡航计划供信号恢复时续飞。 */
    public static boolean pauseForSignal(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        if (s == null || !s.active || s.signalPaused) return false;
        s.signalPaused = true;
        return true;
    }

    /** 信号恢复后续接仅由失联暂停的计划。 */
    public static void resumeAfterSignal(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        if (s != null && s.active) s.signalPaused = false;
    }

    /** 将当前计划写入载具 entity persistentData，覆盖区块卸载与服务器重启。 */
    public static void persist(AbstractVehicle vehicle) {
        if (vehicle == null) return;
        MutableState s = STATES.get(vehicle.getUUID());
        if (s == null) return;
        vehicle.getPersistentData().put("rvpLoiterPlan", serialize(s.snapshot(), s.signalPaused));
    }

    /** 从载具持久数据恢复巡航计划；无有效字段时不创建状态。 */
    public static void restore(AbstractVehicle vehicle) {
        if (vehicle == null) return;
        // 旧存档里的旋翼/地面盘旋计划不能在加载后锁住人工输入。
        if (!supportsLoiter(vehicle)) {
            remove(vehicle.getUUID());
            clearPersistent(vehicle);
            return;
        }
        CompoundTag tag = vehicle.getPersistentData().getCompound("rvpLoiterPlan");
        if (!tag.isEmpty()) restore(vehicle.getUUID(), tag);
    }

    /** 供状态策略测试使用的纯 NBT 快照恢复入口；实体恢复继续走 restore(AbstractVehicle)。 */
    static void restore(UUID uavUuid, CompoundTag tag) {
        if (uavUuid == null || tag == null) return;
        CompoundTag copy = tag.copy();
        try {
            if (!copy.contains("phase") || !copy.contains("centerX") || !copy.contains("centerY")
                    || !copy.contains("centerZ") || !copy.contains("requestedRadius")
                    || !copy.contains("effectiveRadius") || !copy.contains("altitude")) {
                STATES.remove(uavUuid);
                return;
            }
            double centerX = copy.getDouble("centerX");
            double centerY = copy.getDouble("centerY");
            double centerZ = copy.getDouble("centerZ");
            double requestedRadius = copy.getDouble("requestedRadius");
            double effectiveRadius = copy.getDouble("effectiveRadius");
            double altitude = copy.getDouble("altitude");
            if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                    || !Double.isFinite(requestedRadius) || requestedRadius <= 0
                    || !Double.isFinite(effectiveRadius) || effectiveRadius <= 0
                    || !Double.isFinite(altitude)) {
                STATES.remove(uavUuid);
                return;
            }
            MutableState s = STATES.computeIfAbsent(uavUuid, ignored -> new MutableState());
            s.active = copy.getBoolean("active");
            s.planInitialized = !copy.contains("planInitialized") || copy.getBoolean("planInitialized");
            s.signalPaused = copy.getBoolean("signalPaused");
            s.phase = LoiterPhase.valueOf(copy.getString("phase"));
            s.centerX = centerX;
            s.centerY = centerY;
            s.centerZ = centerZ;
            s.radius = requestedRadius;
            s.effectiveRadius = effectiveRadius;
            s.altitude = altitude;
            s.markedCenter = copy.getBoolean("markedCenter");
            s.phaseTickCounter = Math.max(0, copy.getInt("phaseTicks"));
            s.phaseTimeout = Math.max(1, copy.getInt("phaseTimeout"));
            s.followParentUuid = copy.hasUUID("followParent") ? copy.getUUID("followParent") : null;
        } catch (IllegalArgumentException ignored) {
            STATES.remove(uavUuid);
        }
    }

    /** 把运行态快照编码为 Entity persistentData 可保存的值。 */
    static CompoundTag serialize(LoiterState state) {
        return serialize(state, state.signalPaused());
    }

    private static CompoundTag serialize(LoiterState state, boolean signalPaused) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("active", state.active());
        tag.putBoolean("planInitialized", true);
        tag.putBoolean("signalPaused", signalPaused);
        tag.putString("phase", state.phase().name());
        tag.putDouble("centerX", state.centerX());
        tag.putDouble("centerY", state.centerY());
        tag.putDouble("centerZ", state.centerZ());
        tag.putDouble("requestedRadius", state.requestedRadius());
        tag.putDouble("effectiveRadius", state.radius());
        tag.putDouble("altitude", state.altitude());
        tag.putBoolean("markedCenter", state.markedCenter());
        tag.putInt("phaseTicks", state.phaseTickCounter());
        tag.putInt("phaseTimeout", state.phaseTimeout());
        if (state.followParentUuid() != null) tag.putUUID("followParent", state.followParentUuid());
        return tag;
    }

    /** 已确认永久移除时清除实体上的巡航计划。 */
    public static void clearPersistent(AbstractVehicle vehicle) {
        if (vehicle != null) vehicle.getPersistentData().remove("rvpLoiterPlan");
    }

    /** 服务端停服前保留各实体 NBT，之后清理仅驻内存的运行态。 */
    public static void clearRuntime() {
        STATES.clear();
    }
}
