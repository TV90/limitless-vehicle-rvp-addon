package org.ywzj.rvp.uav;

import net.minecraft.world.phys.Vec3;
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

    /** 盘旋阶段。 */
    public enum LoiterPhase {
        /** 爬升到安全高度。 */
        CLIMB,
        /** 朝盘旋圆周直飞。 */
        TRANSIT,
        /** 接近圆周，减速对齐切线。 */
        APPROACH,
        /** 圆周盘旋。 */
        LOITER
    }

    /** 对外不可变快照。 */
    public record LoiterState(
            boolean active,
            LoiterPhase phase,
            double centerX, double centerY, double centerZ,
            double radius,
            double altitude,
            @Nullable UUID followParentUuid,
            boolean markedCenter,
            int signFlipCounter,
            float lastYawError,
            int phaseTickCounter,
            int phaseTimeout
    ) {}

    /** 内部可变状态。 */
    static final class MutableState {
        volatile boolean active = false;
        volatile LoiterPhase phase = LoiterPhase.CLIMB;
        volatile double centerX, centerY, centerZ;
        volatile double radius = 120;
        volatile double altitude = 80;
        @Nullable volatile UUID followParentUuid = null;
        volatile boolean markedCenter = false;
        volatile int signFlipCounter = 0;
        volatile float lastYawError = 0;
        volatile int phaseTickCounter = 0;
        volatile int phaseTimeout = 200;

        LoiterState snapshot() {
            return new LoiterState(
                    active, phase, centerX, centerY, centerZ, radius, altitude,
                    followParentUuid, markedCenter, signFlipCounter, lastYawError,
                    phaseTickCounter, phaseTimeout);
        }
    }

    private static final Map<UUID, MutableState> STATES = new ConcurrentHashMap<>();

    /** 激活盘旋（跟随母车模式）。 */
    public static void enableFollowParent(UUID uavUuid, UUID parentUuid,
                                          double radius, double altitudeOffset,
                                          double parentX, double parentY, double parentZ) {
        MutableState s = STATES.computeIfAbsent(uavUuid, k -> new MutableState());
        s.active = true;
        s.phase = LoiterPhase.CLIMB;
        s.followParentUuid = parentUuid;
        s.markedCenter = false;
        s.radius = radius;
        s.centerY = parentY + altitudeOffset;
        s.centerX = parentX;
        s.centerZ = parentZ;
        s.altitude = parentY + altitudeOffset;
        s.signFlipCounter = 0;
        s.lastYawError = 0;
        s.phaseTickCounter = 0;
        s.phaseTimeout = 200;
    }

    /** 激活盘旋（固定圆心模式，战术地图标记）。 */
    public static void enableMarkedCenter(UUID uavUuid, Vec3 center,
                                          double radius, double altitude) {
        MutableState s = STATES.computeIfAbsent(uavUuid, k -> new MutableState());
        s.active = true;
        s.phase = LoiterPhase.CLIMB;
        s.followParentUuid = null;
        s.markedCenter = true;
        s.centerX = center.x;
        s.centerY = center.y;
        s.centerZ = center.z;
        s.radius = radius;
        s.altitude = altitude;
        s.signFlipCounter = 0;
        s.lastYawError = 0;
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
    }

    /** 关闭盘旋。 */
    public static void disable(UUID uavUuid) {
        MutableState s = STATES.get(uavUuid);
        if (s != null) {
            s.active = false;
        }
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
        return s != null && s.active;
    }
}
