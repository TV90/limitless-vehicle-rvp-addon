package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;

/**
 * [RVP] IR 导引头独立锁定通道——客户端状态表（2026-10-21「连续跟踪锁定」）。
 *
 * <p>key = {@link WeaponUnit} 实例 →（weaponId → 状态）。同一站可能挂多种 IR 弹
 * （视场/驻留参数不同），按 weaponId 分桶防串弹。客户端/服务端各自进程独立，
 * 服务端授权表见 {@code org.ywzj.rvp.weapon.core.RVP_ServerIrSeekerArmedTable}。</p>
 *
 * <p>范式照 {@code org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable}（静态旁路表，
 * mixin 类不持有状态）。清理钩子：玩家离开载具时 {@link #clearAll()}；
 * 换弹种/关导引头时 {@link #reset(WeaponUnit, ResourceLocation)}。</p>
 */
public final class RVP_IrSeekerStateTable {

    private RVP_IrSeekerStateTable() {}

    /** 导引头状态机相位：OFF → SEARCH → TRACKING →（dwell 满）→ ARMED。 */
    public enum Phase {
        OFF, SEARCH, TRACKING, ARMED
    }

    /** 单枚 IR 弹在单个武器站上的导引头状态。 */
    public static final class State {
        /** 状态机相位；初始 OFF。 */
        public Phase phase = Phase.OFF;
        /** 当前候选目标实体 id；-1 = 无候选。 */
        public int candidateId = -1;
        /** 候选在包线内的连续 tick 计数（宽限 tick 不涨不降）。 */
        public int dwellTicks;
        /** 最近一次候选在包线内的 game time（宽限判定基准）。 */
        public long lastInEnvelopeTick;
        /** 已授权（armed）目标实体 id；-1 = 未授权。 */
        public int armedId = -1;
        /** 最近一次 armed 目标在 hold 包线内的 game time（保活判定基准）。 */
        public long lastArmedInEnvelopeTick;
        /** tracker 自己写进站级 lockedEntity 的实体 id；unarm 时只清与它匹配的站级锁，避免误清 EO/RF 锁。 */
        public int lastWrittenStationLockId = -1;
        /** 该状态绑定的弹种 id（防切弹种串状态）。 */
        public ResourceLocation weaponId;
    }

    private static final Map<WeaponUnit, Map<ResourceLocation, State>> STATES = new HashMap<>();

    /** 取（或创建）指定站 + 弹种的状态。 */
    public static State stateFor(WeaponUnit unit, ResourceLocation weaponId) {
        return STATES.computeIfAbsent(unit, u -> new HashMap<>())
                .computeIfAbsent(weaponId, id -> {
                    State state = new State();
                    state.weaponId = id;
                    return state;
                });
    }

    /** 查指定站 + 弹种的状态；无则 null（不创建）。 */
    @Nullable
    public static State find(WeaponUnit unit, ResourceLocation weaponId) {
        Map<ResourceLocation, State> byWeapon = STATES.get(unit);
        return byWeapon == null ? null : byWeapon.get(weaponId);
    }

    /** 重置指定站 + 弹种的状态（保留 weaponId 绑定）。 */
    public static void reset(WeaponUnit unit, ResourceLocation weaponId) {
        Map<ResourceLocation, State> byWeapon = STATES.get(unit);
        if (byWeapon != null) {
            byWeapon.remove(weaponId);
        }
    }

    /** 清空指定站的全部 IR 状态（站被移除/切换操作站时）。 */
    public static void clearUnit(WeaponUnit unit) {
        STATES.remove(unit);
    }

    /** 清空全表（玩家离开载具时）。 */
    public static void clearAll() {
        STATES.clear();
    }

    /** 全表是否为空（维护逻辑的快速短路用）。 */
    public static boolean isAllEmpty() {
        return STATES.isEmpty();
    }

    /** 指定站全部弹种状态的只读快照（遍历期间防并发修改）。 */
    public static Map<ResourceLocation, State> snapshot(WeaponUnit unit) {
        Map<ResourceLocation, State> byWeapon = STATES.get(unit);
        return byWeapon == null ? Map.of() : Map.copyOf(byWeapon);
    }

    /** 全表只读快照。 */
    public static Map<WeaponUnit, Map<ResourceLocation, State>> snapshotAll() {
        Map<WeaponUnit, Map<ResourceLocation, State>> out = new HashMap<>();
        for (Map.Entry<WeaponUnit, Map<ResourceLocation, State>> e : STATES.entrySet()) {
            out.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return out;
    }

    /** @return 指定站 + 弹种当前 armed 的目标实体；未授权/目标已失效返回 null。 */
    @Nullable
    public static Entity getArmedEntity(WeaponUnit unit, @Nullable ResourceLocation weaponId) {
        State state = find(unit, weaponId);
        if (state == null || state.armedId == -1 || unit.getVehicle() == null) {
            return null;
        }
        Entity target = unit.getVehicle().level().getEntity(state.armedId);
        return target != null && target.isAlive() ? target : null;
    }

    /** @return 指定站 + 弹种是否处于 ARMED 相位（不解析实体，纯状态判定）。 */
    public static boolean isArmed(WeaponUnit unit, @Nullable ResourceLocation weaponId) {
        State state = find(unit, weaponId);
        return state != null && state.phase == Phase.ARMED && state.armedId != -1;
    }
}
