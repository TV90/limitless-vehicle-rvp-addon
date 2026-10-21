package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.network.C2SIrSeekerArmed;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataIR;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;

import java.util.HashMap;
import java.util.Map;

/**
 * [RVP] IR 导引头连续跟踪 tracker（客户端权威，2026-10-21）。
 *
 * <p>语义（方案 D1/D2）：IR 锁定（armed）= 同一目标连续 {@code seeker_lock_dwell_tick} tick
 * 待在该导引头自己的跟踪包线内；dwell 满才写站级锁 + 发 C2SIrSeekerArmed + 出现锁定视觉。
 * 目标离开包线进宽限，超宽限清零；armed 后由 {@link #tick(Minecraft)} 做 hold 包线保活。</p>
 *
 * <p><b>炮塔不参与</b>（2026-10-21 用户定）：本类从不写 {@code xAimRot/yAimRot}——
 * 追踪期与锁定后炮塔都保持玩家鼠标控制，只有头瞄框（视觉层）跟随目标。</p>
 *
 * <p><b>2026-10-21 夜间修复（详见 docs/plan/IR导引头锁定失效诊断_20261021.md）</b>：
 * ① 状态键统一归一到根武器站（与发射门/HUD/服务端表同口径）；
 * ② 保活解包 agent / 多弹种包装后再判 RVP_WeaponBase（否则 armed 下一 tick 被当"武器没了"清掉）；
 * ③ 实现 C2SIrSeekerArmed 上报（原为空实现，服务端表永远为空 ⇒ 全部拒射）；
 * ④ dwell 按真实 tick 差累加（HMD 每 2 tick 扫一次，原实现延迟翻倍）；
 * ⑤ 炮塔全程不强制转向目标：IR 通道只维护候选/dwell/armed，头瞄框跟随由 HMD 视觉层负责；
 *    本体火控的站级锁定瞄准在 IR 通道占用期间被屏蔽（见 {@link #isIrLockActive}）。</p>
 */
public final class RVP_IrSeekerTracker {

    private RVP_IrSeekerTracker() {}

    /** 同 tick 防重入（mixin tickFor / HMD feed 可能同 tick 多路到达）。 */
    private static final Map<String, Long> LAST_SCAN_TICK = new HashMap<>();

    /**
     * 全局维护（每客户端 tick）：玩家不在载具 → 清全表；否则对当前操作站做上下文失效清理
     * （关导引头等）与 ARMED 保活（存活 + hold 包线 + 伺服 + 超时掉锁）。
     */
    public static void tick(Minecraft mc) {
        WeaponUnit operator = LocalVehiclePlayer.instance.onVehicle()
                ? LocalVehiclePlayer.instance.getWeaponUnit()
                : null;
        WeaponUnit root = rootOf(operator);
        if (root == null || root.getVehicle() == null) {
            if (!RVP_IrSeekerStateTable.isAllEmpty()) {
                forEachArmed((u, state) -> unarm(u, state, "vehicle-left"));
                RVP_IrSeekerStateTable.clearAll();
            }
            LAST_SCAN_TICK.clear();
            return;
        }
        // 导引头开关是"操作站"的状态（玩家直接操纵子站时 root 上可能为 false，不能拿 root 判）。
        resetIfContextBroken(root, operator != null ? operator : root);
        maintainArmed(root);
    }

    /**
     * 非 HMD IR 路径入口（mixin 对任意传感器的 IR 发射武器都会调用）：
     * 扫描候选 → dwell 累积 → 满则 armed。
     */
    public static void tickFor(WeaponUnit unit, RVP_WeaponBase rvpWeapon) {
        RVP_WeaponData data = rvpWeapon.getData();
        if (data.isEnableIrHmd()) {
            return; // HMD 弹由头瞄通道 feedHmdCandidate 喂入，避免双通道抢目标
        }
        // 几何判定用"武器自己的站"（世界枢轴/瞄准轴正确），状态键用根站（与发射门/HUD 一致）。
        WeaponUnit geo = rvpWeapon.getWeaponUnit() != null ? rvpWeapon.getWeaponUnit() : unit;
        if (geo == null || geo.getVehicle() == null) {
            return;
        }
        WeaponUnit root = rootOf(geo);
        ResourceLocation weaponId = data.getWeaponId();
        if (alreadyScannedThisTick(root, weaponId)) {
            return;
        }
        long gameTime = geo.getVehicle().level().getGameTime();
        markScannedThisTick(root, weaponId, gameTime);
        RVP_IrSeekerStateTable.State st = RVP_IrSeekerStateTable.stateFor(root, weaponId);
        int dwell = RVP_GuidanceDataIR.resolveLockDwellTick(data.getGuidanceData());
        int grace = RVP_GuidanceDataIR.resolveDwellGraceTick(data.getGuidanceData());

        // 候选粘滞：当前候选仍存活且在包线内 → 不重扫（防伺服跟随导致左右横跳）。
        Entity best;
        Entity sticky = st.candidateId == -1 ? null
                : geo.getVehicle().level().getEntity(st.candidateId);
        if (sticky != null && sticky.isAlive()
                && RVP_IrLockHelper.isTargetWithinAcquireLimits(geo, sticky, data)) {
            best = sticky;
        } else {
            best = Infrared.findTarget(geo,
                    RVP_IrLockHelper.halfAngleFromFull(data.resolveLaunchSeekerFullFov()));
            if (best != null && !RVP_IrLockHelper.isTargetWithinAcquireLimits(geo, best, data)) {
                best = null;
            }
        }

        if (best == null) {
            if (st.armedId != -1) {
                return; // ARMED 保活由 tick(Minecraft) 统一处理
            }
            if (gameTime - st.lastInEnvelopeTick > grace) {
                st.dwellTicks = 0;
                st.candidateId = -1;
                st.phase = RVP_IrSeekerStateTable.Phase.SEARCH;
            } else if (st.phase == RVP_IrSeekerStateTable.Phase.OFF) {
                st.phase = RVP_IrSeekerStateTable.Phase.SEARCH;
            }
            return;
        }

        if (best.getId() != st.candidateId) {
            // 换候选（含新捕获）→ 驻留清零重算
            st.candidateId = best.getId();
            st.dwellTicks = 0;
            st.lastInEnvelopeTick = 0L;
        }
        advanceDwell(st, gameTime);
        st.phase = RVP_IrSeekerStateTable.Phase.TRACKING;
        // D1：追踪期即自动跟随（伺服），锁定延迟手感来源是 dwell 而不是"锁上才转塔"。
        // 不写 xAimRot：炮塔保持玩家/本体火控控制，IR 只做候选与 dwell（头瞄框跟随由视觉层负责）。
        // 旧实现在此 aim，与本体每 tick 的鼠标瞄准交替写 xAimRot ⇒ 准星 10Hz 抖动（2026-10-21 反馈）。
        if (st.dwellTicks >= dwell && st.armedId == -1) {
            arm(root, geo, data, best, st);
        }
    }

    /**
     * HMD 路径喂入口：ClientHmdState 扫描得到候选后调用（替代原 confirmIrLock 直锁）。
     * dwell 满才 arm（返回 true = 本 tick 发生 armed 跃迁，调用方可据此出提示）。
     */
    public static boolean feedHmdCandidate(WeaponUnit unit, RVP_WeaponData data,
                                           Entity candidate, double effectiveAngleDeg) {
        if (unit == null || unit.getVehicle() == null || data == null) {
            return false;
        }
        WeaponUnit root = rootOf(unit);
        ResourceLocation weaponId = data.getWeaponId();
        if (alreadyScannedThisTick(root, weaponId)) {
            return false;
        }
        long gameTime = unit.getVehicle().level().getGameTime();
        markScannedThisTick(root, weaponId, gameTime);
        RVP_IrSeekerStateTable.State st = RVP_IrSeekerStateTable.stateFor(root, weaponId);
        int dwell = RVP_GuidanceDataIR.resolveLockDwellTick(data.getGuidanceData());
        int grace = RVP_GuidanceDataIR.resolveDwellGraceTick(data.getGuidanceData());

        if (candidate == null || !candidate.isAlive()) {
            if (st.armedId == -1 && gameTime - st.lastInEnvelopeTick > grace) {
                st.dwellTicks = 0;
                st.candidateId = -1;
                st.phase = RVP_IrSeekerStateTable.Phase.SEARCH;
            }
            return false;
        }
        if (st.candidateId != candidate.getId()) {
            st.candidateId = candidate.getId();
            st.dwellTicks = 0;
            st.lastInEnvelopeTick = 0L;
        }
        advanceDwell(st, gameTime);
        st.phase = RVP_IrSeekerStateTable.Phase.TRACKING;
        // 2026-10-21 用户纠正 2：追踪期就要"头瞄套住并跟随"（改前手感），锁定视觉仍只在 armed 后出。
        // 不写 xAimRot：炮塔不强制转向，HMD 只负责框跟随 + dwell 累积。
        if (st.dwellTicks >= dwell && st.armedId == -1) {
            arm(root, unit, data, candidate, st);
            return true;
        }
        return false;
    }

    /** dwell 按真实 tick 差累加（HMD 扫描间隔 2 tick 时不再让延迟翻倍）。 */
    private static void advanceDwell(RVP_IrSeekerStateTable.State st, long gameTime) {
        int delta = st.lastInEnvelopeTick <= 0L ? 1
                : (int) Math.max(1L, Math.min(gameTime - st.lastInEnvelopeTick, 5L));
        st.dwellTicks += delta;
        st.lastInEnvelopeTick = gameTime;
    }

    // ===================== 内部：arm / unarm / 维护 =====================

    /** 落锁：写站级锁（记 lastWrittenStationLockId）+ 上报 C2SIrSeekerArmed + 置 ARMED。 */
    private static void arm(WeaponUnit root, WeaponUnit geo, RVP_WeaponData data, Entity target,
                            RVP_IrSeekerStateTable.State st) {
        st.armedId = target.getId();
        st.lastArmedInEnvelopeTick = root.getVehicle().level().getGameTime();
        st.phase = RVP_IrSeekerStateTable.Phase.ARMED;
        root.setLockedEntity(target);
        st.lastWrittenStationLockId = target.getId();
        if (data.isEnableIrHmd()) {
            RVP_ClientHmdState.getInstance().noteTrackerIrArm(target);
        }
        sendArmedUpdate(root, data.getWeaponId(), target.getId(), true);
    }

    /** 掉锁：只清自己写的站级锁 + 上报解锁 + 整状态重置（ARMED → OFF）。 */
    private static void unarm(WeaponUnit root, RVP_IrSeekerStateTable.State st, String reason) {
        if (root != null && st.lastWrittenStationLockId != -1) {
            Entity stationLock = root.getLockedEntity();
            if (stationLock != null && stationLock.getId() == st.lastWrittenStationLockId) {
                root.setLockedEntity(null);
            }
            st.lastWrittenStationLockId = -1;
        }
        if (st.armedId != -1) {
            sendArmedUpdate(root, st.weaponId, -1, false);
            RVP_ClientHmdState.getInstance().noteTrackerIrUnarm(st.armedId);
            org.ywzj.rvp.client.debug.RVP_DebugStateLogs.logIrHms(
                    "ir-seeker unarm reason=" + reason + " target=" + st.armedId);
        }
        st.armedId = -1;
        st.candidateId = -1;
        st.dwellTicks = 0;
        st.lastInEnvelopeTick = 0L;
        st.phase = RVP_IrSeekerStateTable.Phase.OFF;
    }

    /** 上下文失效（关导引头等）→ 解除自己写的锁并清状态。 */
    private static void resetIfContextBroken(WeaponUnit root, WeaponUnit seekerOwner) {
        for (Map.Entry<ResourceLocation, RVP_IrSeekerStateTable.State> entry
                : RVP_IrSeekerStateTable.snapshot(root).entrySet()) {
            RVP_IrSeekerStateTable.State st = entry.getValue();
            boolean broken = seekerOwner == null || !seekerOwner.isSeekerOn();
            if (broken && st.armedId != -1) {
                unarm(root, st, "context-broken");
            }
            if (broken) {
                RVP_IrSeekerStateTable.reset(root, entry.getKey());
            }
        }
    }

    /** ARMED 保活：存活 + hold 包线 + 超时掉锁（不驱动炮塔）。 */
    private static void maintainArmed(WeaponUnit root) {
        if (root == null || root.getVehicle() == null) {
            return;
        }
        for (Map.Entry<ResourceLocation, RVP_IrSeekerStateTable.State> entry
                : RVP_IrSeekerStateTable.snapshot(root).entrySet()) {
            RVP_IrSeekerStateTable.State st = entry.getValue();
            if (st.armedId == -1) {
                continue;
            }
            Entity target = root.getVehicle().level().getEntity(st.armedId);
            if (target == null || !target.isAlive()) {
                unarm(root, st, "target-gone");
                continue;
            }
            RVP_WeaponBase rvpWeapon = resolveRvpWeapon(root, entry.getKey());
            if (rvpWeapon == null) {
                unarm(root, st, "weapon-gone");
                continue;
            }
            RVP_WeaponData data = rvpWeapon.getData();
            WeaponUnit geo = rvpWeapon.getWeaponUnit() != null ? rvpWeapon.getWeaponUnit() : root;
            if (RVP_IrLockHelper.isTargetWithinHoldLimits(geo, target, data)) {
                st.lastArmedInEnvelopeTick = root.getVehicle().level().getGameTime();
            } else if (root.getVehicle().level().getGameTime() - st.lastArmedInEnvelopeTick
                    > RVP_GuidanceDataIR.resolveHoldGraceTick(data.getGuidanceData())) {
                unarm(root, st, "hold-expired");
            }
        }
    }

    /**
     * 该站（按根站归一）是否处在 IR 通道占用期（TRACKING 有活候选 / ARMED 有活目标）。
     *
     * <p>仅用于屏蔽本体 {@code WeaponUnit.tickFireControl} 的「站级锁定自动瞄准」：
     * IR 锁定后炮塔不再被强行转向目标（HUD 锁定框、弹体目标读的是站级锁/armed 表，不受影响）。
     * 鼠标瞄准不屏蔽——炮塔始终由玩家控制。</p>
     */
    public static boolean isIrLockActive(@Nullable WeaponUnit unit) {
        WeaponUnit root = rootOf(unit);
        if (root == null || root.getVehicle() == null) {
            return false;
        }
        long gameTime = root.getVehicle().level().getGameTime();
        for (Map.Entry<ResourceLocation, RVP_IrSeekerStateTable.State> entry
                : RVP_IrSeekerStateTable.snapshot(root).entrySet()) {
            RVP_IrSeekerStateTable.State st = entry.getValue();
            if (st.armedId != -1) {
                if (resolveRvpWeapon(root, entry.getKey()) != null) {
                    return true;
                }
                continue;
            }
            if (st.phase != RVP_IrSeekerStateTable.Phase.TRACKING || st.candidateId == -1) {
                continue;
            }
            if (gameTime - st.lastInEnvelopeTick <= resolveDwellGrace(root, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 追踪期候选目标（已捕获、dwell 未满）——供 HUD 在头瞄 IR 框下显示“截获中”。
     *
     * <p>armed 之后返回 null（锁定视觉改由红圈/锁定框表达）；超过 dwell 宽限未再喂入也返回
     * null，避免切弹种后残留文案。</p>
     */
    @Nullable
    public static Entity getTrackingCandidate(@Nullable WeaponUnit unit, @Nullable ResourceLocation weaponId) {
        WeaponUnit root = rootOf(unit);
        if (root == null || root.getVehicle() == null || weaponId == null) {
            return null;
        }
        RVP_IrSeekerStateTable.State st = RVP_IrSeekerStateTable.find(root, weaponId);
        if (st == null || st.armedId != -1
                || st.phase != RVP_IrSeekerStateTable.Phase.TRACKING || st.candidateId == -1) {
            return null;
        }
        RVP_WeaponBase rvpWeapon = resolveRvpWeapon(root, weaponId);
        if (rvpWeapon == null) {
            return null;
        }
        long gameTime = root.getVehicle().level().getGameTime();
        if (gameTime - st.lastInEnvelopeTick
                > RVP_GuidanceDataIR.resolveDwellGraceTick(rvpWeapon.getData().getGuidanceData())) {
            return null;
        }
        Entity candidate = root.getVehicle().level().getEntity(st.candidateId);
        return candidate != null && candidate.isAlive() ? candidate : null;
    }

    /** 取指定弹种的 dwell 宽限；武器已不可解析时按默认 3。 */
    private static int resolveDwellGrace(WeaponUnit root, ResourceLocation weaponId) {
        RVP_WeaponBase rvpWeapon = resolveRvpWeapon(root, weaponId);
        return rvpWeapon == null ? 3
                : RVP_GuidanceDataIR.resolveDwellGraceTick(rvpWeapon.getData().getGuidanceData());
    }

    /** 按 weaponId 在根站（含子站）解析 RVP 武器；解包 agent / 多弹种包装后再判。 */
    private static RVP_WeaponBase resolveRvpWeapon(WeaponUnit root, ResourceLocation weaponId) {
        RVP_WeaponBase found = findOn(root, weaponId);
        if (found != null) {
            return found;
        }
        for (WeaponUnit sub : root.getSubWeaponUnits()) {
            found = findOn(sub, weaponId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static RVP_WeaponBase findOn(WeaponUnit unit, ResourceLocation weaponId) {
        if (unit == null || weaponId == null) {
            return null;
        }
        for (AbstractVehicleWeapon<?> raw : unit.getIndexedWeapons()) {
            AbstractVehicleWeapon<?> resolved = RVP_WeaponResolveHelper.unwrap(raw);
            if (resolved instanceof RVP_WeaponBase rvp
                    && weaponId.equals(rvp.getData().getWeaponId())) {
                return rvp;
            }
        }
        return null;
    }

    private static WeaponUnit rootOf(WeaponUnit unit) {
        if (unit == null) {
            return null;
        }
        WeaponUnit root = unit.getRootParentWeaponUnit();
        return root != null ? root : unit;
    }

    private static boolean alreadyScannedThisTick(WeaponUnit root, ResourceLocation weaponId) {
        if (root == null) {
            return false;
        }
        Long last = LAST_SCAN_TICK.get(scanKey(root, weaponId));
        long now = root.getVehicle() != null ? root.getVehicle().level().getGameTime() : Long.MIN_VALUE;
        return last != null && last == now;
    }

    private static void markScannedThisTick(WeaponUnit root, ResourceLocation weaponId, long gameTime) {
        if (root != null) {
            LAST_SCAN_TICK.put(scanKey(root, weaponId), gameTime);
        }
    }

    private static String scanKey(WeaponUnit root, ResourceLocation weaponId) {
        return root + "@" + weaponId;
    }

    /** 上报 armed 跃迁（客户端 → 服务端授权表）。只在 arm/unarm 时调用。 */
    private static void sendArmedUpdate(WeaponUnit unit, ResourceLocation weaponId,
                                        int entityId, boolean armed) {
        WeaponUnit root = rootOf(unit);
        if (root == null || root.getVehicle() == null || weaponId == null) {
            return;
        }
        int stationIndex = stationIndexOf(root.getVehicle(), root);
        if (stationIndex < 0) {
            return;
        }
        RVP_Network.CHANNEL.sendToServer(new C2SIrSeekerArmed(
                root.getVehicle().getId(), stationIndex, weaponId, entityId, armed));
    }

    /** 站序（与 RVP_ProjectileSpawner / C2SIrSeekerArmed.handle 同一语义）。 */
    private static int stationIndexOf(AbstractVehicle vehicle, WeaponUnit unit) {
        if (vehicle == null || unit == null) {
            return -1;
        }
        for (int i = 0; i < vehicle.getPartUnits().size(); i++) {
            if (vehicle.getPartUnits().get(i) == unit) {
                return i;
            }
        }
        return -1;
    }

    private interface ArmedVisitor {
        void visit(WeaponUnit unit, RVP_IrSeekerStateTable.State state);
    }

    private static void forEachArmed(ArmedVisitor visitor) {
        forEachState((unit, weaponId, state) -> {
            if (state.armedId != -1) {
                visitor.visit(unit, state);
            }
        });
    }

    private interface StateVisitor {
        void visit(WeaponUnit unit, ResourceLocation weaponId,
                   RVP_IrSeekerStateTable.State state);
    }

    private static void forEachState(StateVisitor visitor) {
        for (Map.Entry<WeaponUnit, Map<ResourceLocation, RVP_IrSeekerStateTable.State>> byWeapon
                : RVP_IrSeekerStateTable.snapshotAll().entrySet()) {
            for (Map.Entry<ResourceLocation, RVP_IrSeekerStateTable.State> entry
                    : byWeapon.getValue().entrySet()) {
                visitor.visit(byWeapon.getKey(), entry.getKey(), entry.getValue());
            }
        }
    }
}
