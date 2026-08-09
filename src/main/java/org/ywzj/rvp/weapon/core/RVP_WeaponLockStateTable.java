package org.ywzj.rvp.weapon.core;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;

/**
 * B3 旁路状态表：替代被删的 {@code WeaponUnitArmMixin} /
 * {@code WeaponUnitExternalRadarLockMixin} / {@code WeaponUnitPendingRadarLockMixin}
 * 三个接口注入（mixin 移除后 {@code instanceof XxxExt} 恒为 false，链路已断）。
 *
 * <p>原 3 个 Ext 接口的状态字段全部迁移到本表（以 {@link WeaponUnit} 实例为 key，
 * 客户端/服务端各自进程独立，与原字段语义一致）。RVP 各调用方从
 * {@code instanceof XxxExt} + 接口调用改为直接调用本表静态方法。</p>
 */
public final class RVP_WeaponLockStateTable {

    private RVP_WeaponLockStateTable() {}

    private static final Map<WeaponUnit, ArmState> ARM_STATES = new HashMap<>();
    private static final Map<WeaponUnit, ExternalLockState> EXTERNAL_STATES = new HashMap<>();
    private static final Map<WeaponUnit, Integer> PENDING_IDS = new HashMap<>();

    // ===================== ARM 预选目标 =====================

    public static int getArmPreselectedVehicleId(WeaponUnit unit) {
        ArmState state = ARM_STATES.get(unit);
        return state != null ? state.vehicleId : -1;
    }

    public static int getArmPreselectedRadarIndex(WeaponUnit unit) {
        ArmState state = ARM_STATES.get(unit);
        return state != null ? state.radarIndex : -1;
    }

    @Nullable
    public static Vec3 getArmPreselectedPos(WeaponUnit unit) {
        ArmState state = ARM_STATES.get(unit);
        if (state == null || !state.hasPos) {
            return null;
        }
        return new Vec3(state.x, state.y, state.z);
    }

    public static void setArmPreselected(WeaponUnit unit, int vehicleId, int radarIndex, @Nullable Vec3 pos) {
        ArmState state = ARM_STATES.computeIfAbsent(unit, u -> new ArmState());
        state.vehicleId = vehicleId;
        state.radarIndex = radarIndex;
        if (pos == null) {
            state.hasPos = false;
        } else {
            state.hasPos = true;
            state.x = pos.x;
            state.y = pos.y;
            state.z = pos.z;
        }
    }

    public static void clearArmPreselected(WeaponUnit unit) {
        ARM_STATES.remove(unit);
    }

    // ===================== 外部雷达锁定 =====================

    public static int getExternalRadarRequestedEntityId(WeaponUnit unit) {
        ExternalLockState state = EXTERNAL_STATES.get(unit);
        return state != null ? state.requestedEntityId : Integer.MIN_VALUE;
    }

    public static void setExternalRadarRequestedEntityId(WeaponUnit unit, int entityId) {
        EXTERNAL_STATES.computeIfAbsent(unit, u -> new ExternalLockState()).requestedEntityId = entityId;
    }

    public static void clearExternalRadarRequestedEntityId(WeaponUnit unit) {
        ExternalLockState state = EXTERNAL_STATES.get(unit);
        if (state != null) {
            state.requestedEntityId = Integer.MIN_VALUE;
        }
    }

    public static int getExternalRadarLockedEntityId(WeaponUnit unit) {
        ExternalLockState state = EXTERNAL_STATES.get(unit);
        return state != null ? state.lockedEntityId : Integer.MIN_VALUE;
    }

    public static void setExternalRadarLockedEntityId(WeaponUnit unit, int entityId) {
        EXTERNAL_STATES.computeIfAbsent(unit, u -> new ExternalLockState()).lockedEntityId = entityId;
    }

    public static void clearExternalRadarLockedEntityId(WeaponUnit unit) {
        ExternalLockState state = EXTERNAL_STATES.get(unit);
        if (state != null) {
            state.lockedEntityId = Integer.MIN_VALUE;
        }
    }

    // ===================== 待定雷达锁定 =====================

    public static int getPendingRadarLockEntityId(WeaponUnit unit) {
        Integer pendingId = PENDING_IDS.get(unit);
        return pendingId != null ? pendingId : Integer.MIN_VALUE;
    }

    public static void setPendingRadarLockEntityId(WeaponUnit unit, int entityId) {
        PENDING_IDS.put(unit, entityId);
    }

    public static void clearPendingRadarLockEntityId(WeaponUnit unit) {
        PENDING_IDS.remove(unit);
    }

    private static final class ArmState {
        int vehicleId = -1;
        int radarIndex = -1;
        boolean hasPos;
        double x;
        double y;
        double z;
    }

    private static final class ExternalLockState {
        int requestedEntityId = Integer.MIN_VALUE;
        int lockedEntityId = Integer.MIN_VALUE;
    }
}
