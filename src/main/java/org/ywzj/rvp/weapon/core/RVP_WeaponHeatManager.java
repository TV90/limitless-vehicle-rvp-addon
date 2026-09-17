package org.ywzj.rvp.weapon.core;

import org.ywzj.rvp.config.RVP_VehicleWeaponHeatConfig;
import org.ywzj.rvp.config.RVP_VehicleWeaponHeatConfigCache;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class RVP_WeaponHeatManager {

    private static final Map<AbstractVehicle, Map<RVP_VehicleWeaponHeatConfigCache.SlotKey, HeatState>> VEHICLE_HEAT = new WeakHashMap<>();

    /**
     * 武器级热量的跨端共享表（对应武器 JSON 的 {@code fire_data.heat_count} / {@code max_heat_count}）。
     *
     * <p><b>为什么不放在武器实例字段</b>：客户端与服务端各持一个武器实例，实例字段互不同步，
     * 于是单机下客户端 HUD 永远读不到服务端算出的热量（恒显示 {@code HEAT 0%}），
     * 而服务端其实早已按过热限速（2026-09-17 实机确认）。</p>
     *
     * <p><b>为什么这张表能共享</b>：它是 {@code static}，且外层键是载具实体——
     * MC 的 {@code Entity} 覆写了 {@code equals}/{@code hashCode} 并<b>按实体 id 比较</b>，
     * 而单机（integrated server）下客户端实体与服务端实体 id 相同，因此两端命中同一条目，
     * 热量天然共享；专用服务器上客户端与服务端分属不同 JVM，各持一张表，互不影响。</p>
     */
    private static final Map<AbstractVehicle, Map<WeaponKey, HeatState>> WEAPON_HEAT = new WeakHashMap<>();

    /** 未启用过热（{@code max_heat_count <= 0}）时共用的占位状态，避免调用方拿到 null。 */
    private static final HeatState DISABLED_STATE = new HeatState();

    /**
     * 武器级热量的键：同一载具内的「武器站 id + 槽位序号」。
     * 两端由同一套载具 JSON 按同一顺序构造武器，故两端算出的键必然一致。
     */
    public record WeaponKey(String partUnitId, int weaponIndex) {}

    /**
     * 全部热量表/热量状态的跨线程锁（2026-09-18 崩溃修复）。
     *
     * <p>单机（integrated server）是同一 JVM 两个线程：<b>服务端线程</b>（载具武器 tick →
     * {@code RVP_WeaponFireController.tick}）与<b>客户端线程</b>（客户端武器副本 tick +
     * 热 HUD 每帧读取 + 激光过热渲染门 {@code RVP_LaserWeapons.canRenderBeam}）都会进来，
     * 普通 HashMap 并发 computeIfAbsent/扩容抛 ConcurrentModificationException
     * （crash-2026-09-18_05.10.20-server，与 2026-09-15 LauncherDeployStateMachine CME 同类）。
     * 但本表的设计目的就是<b>跨端共享条目</b>（客户端读服务端热量），不能按端拆表，改用互斥锁；
     * 锁同时覆盖 {@link HeatState} 的读改写，保证 {@link #tickState} 原子。
     * 全部 public 入口必须包在锁内，私有 helper 只允许从锁内调用。</p>
     */
    private static final Object LOCK = new Object();

    private RVP_WeaponHeatManager() {}

    public static void tick(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            HeatSpec spec = resolveSpec(weapon);
            if (!spec.enabled()) {
                return;
            }
            tickState(spec.state(), tickClock(weapon));
        }
    }

    public static boolean canShoot(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            HeatSpec spec = resolveSpec(weapon);
            if (!spec.enabled()) {
                return true;
            }
            HeatState state = spec.state();
            tickState(state, tickClock(weapon));
            return state.currentHeat < spec.maxHeatCount();
        }
    }

    public static void onShotFired(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            HeatSpec spec = resolveSpec(weapon);
            if (!spec.enabled()) {
                return;
            }
            HeatState state = spec.state();
            tickState(state, tickClock(weapon));
            state.currentHeat += spec.heatCount();
            if (state.currentHeat >= spec.maxHeatCount()) {
                state.currentHeat += spec.overheatExtraHeat();
            }
        }
    }

    public static int currentHeat(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            HeatSpec spec = resolveSpec(weapon);
            if (!spec.enabled()) {
                return 0;
            }
            HeatState state = spec.state();
            // 读取时推进冷却：恒定速率下同一游戏 tick 内多次读取 elapsed=0，
            // 不会加速冷却。保证客户端 HUD 在没有射击事件驱动时也能实时反映冷却进度。
            tickState(state, tickClock(weapon));
            return Math.max(state.currentHeat, 0);
        }
    }

    public static int maxHeat(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            HeatSpec spec = resolveSpec(weapon);
            return spec.enabled() ? spec.maxHeatCount() : 0;
        }
    }

    public static float heatRatio(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            int max = maxHeat(weapon);
            if (max <= 0) {
                return 0f;
            }
            return Math.min(currentHeat(weapon) / (float) max, 1.5f);
        }
    }

    public static boolean hasHeat(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            return maxHeat(weapon) > 0;
        }
    }

    public static boolean isOverheated(RVP_WeaponBase weapon) {
        synchronized (LOCK) {
            int max = maxHeat(weapon);
            return max > 0 && currentHeat(weapon) >= max;
        }
    }

    /**
     * 双端一致的冷却时钟（2026-09-18 修复）：必须用世界 gameTime（服务端随时间包同步到客户端，
     * 两端同域），不能用实体 {@code tickCount}——客户端/服务端实体的 tickCount 是两个独立计数域、
     * 互不同步，双端交织写 {@code lastTick} 后另一端会算出巨大 elapsed 把热量瞬间清零
     * （锁解决崩溃后仍会踩的语义雷：过热永远攒不起来）。±1 tick 的同步抖动对显示/门控无感知。
     */
    private static long tickClock(RVP_WeaponBase weapon) {
        return weapon.getVehicle().level().getGameTime();
    }

    private static HeatSpec resolveSpec(RVP_WeaponBase weapon) {
        RVP_VehicleWeaponHeatConfigCache.Resolved vehicleResolved = RVP_VehicleWeaponHeatConfigCache.resolve(weapon);
        if (vehicleResolved != null) {
            HeatState state = VEHICLE_HEAT
                    .computeIfAbsent(weapon.getVehicle(), ignored -> new HashMap<>())
                    .computeIfAbsent(vehicleResolved.key(), ignored -> new HeatState());
            RVP_VehicleWeaponHeatConfig config = vehicleResolved.config();
            return new HeatSpec(state, config.heatCount(), config.maxHeatCount(), config.overheatExtraHeat());
        }

        RVP_FireData fire = weapon.getData().getFireData();
        return new HeatSpec(weaponHeatState(weapon), fire.getHeatCount(), fire.getMaxHeatCount(), fire.getOverheatExtraHeat());
    }

    /**
     * 取武器级热量状态（跨端共享，见 {@link #WEAPON_HEAT}）。
     *
     * <p>未启用过热（{@code max_heat_count <= 0}）时<b>不建条目</b>并返回共用占位状态，
     * 避免为全包每个未配热量的武器都在表里堆积条目；此时 {@code HeatSpec.enabled()} 为 false，
     * 各对外方法都会提前 return，不会读到该状态。</p>
     */
    private static HeatState weaponHeatState(RVP_WeaponBase weapon) {
        if (weapon.getData().getFireData().getMaxHeatCount() <= 0) {
            return DISABLED_STATE;
        }
        return WEAPON_HEAT
                .computeIfAbsent(weapon.getVehicle(), ignored -> new HashMap<>())
                .computeIfAbsent(weaponKey(weapon), ignored -> new HeatState());
    }

    /** 由武器站 id + 武器序号组成跨端一致的键；站 id 缺失时退化为空串（仍能按序号区分）。 */
    private static WeaponKey weaponKey(RVP_WeaponBase weapon) {
        WeaponUnit unit = weapon.getWeaponUnit();
        String partId = unit == null || unit.getId() == null ? "" : unit.getId();
        return new WeaponKey(partId, weapon.getIndex());
    }

    private static void tickState(HeatState state, long tickCount) {
        if (state.lastTick == Long.MIN_VALUE) {
            state.lastTick = tickCount;
            return;
        }
        long elapsed = Math.max(0, tickCount - state.lastTick);
        state.lastTick = tickCount;
        if (elapsed > 0) {
            // 恒定冷却速率：每 tick 固定减 1，避免旧算法（cooldownSpeed 累积加速）导致越冷越快；
            // 长时间无访问后的首次读取会把间隔整段冷却完（elapsed 全额扣减，钳 0），语义正确。
            state.currentHeat = (int) Math.max(0, state.currentHeat - elapsed);
        }
    }

    public static final class HeatState {
        private int currentHeat;
        /** 上次推进冷却的世界 gameTime（long，防老存档 gameTime 超 int 溢出）。 */
        private long lastTick = Long.MIN_VALUE;
    }

    private record HeatSpec(
            HeatState state,
            int heatCount,
            int maxHeatCount,
            int overheatExtraHeat
    ) {
        boolean enabled() {
            return maxHeatCount > 0;
        }
    }
}
