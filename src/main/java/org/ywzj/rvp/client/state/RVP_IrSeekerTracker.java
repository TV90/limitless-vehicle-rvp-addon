package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataIR;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.util.RVP_AimPointResolver;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;

import java.util.HashMap;
import java.util.Map;

/**
 * [RVP] IR 导引头连续跟踪 tracker（客户端权威，2026-10-21）。
 *
 * <p>语义（方案 D1/D2）：IR 锁定（armed）= 同一目标连续 {@code seeker_lock_dwell_tick} tick
 * 待在该导引头自己的跟踪包线内。追踪期武器站即 {@code aim(候选中心)} 自动跟随（自动锁定延迟
 * 手感）；dwell 满才写站级锁 + 发 C2SIrSeekerArmed + 出现锁定视觉。目标离开包线进宽限，
 * 超宽限清零；armed 后由 {@link #tick(Minecraft)} 做 hold 包线保活。</p>
 *
 * <p>入口三个：① {@link #tick(Minecraft)}（ClientEvents 每客户端 tick，全局维护 + ARMED 保活）；
 * ② {@link #tickFor(WeaponUnit, RVP_WeaponBase)}（非 HMD 路径，mixin IR 分支一行转发）；
 * ③ {@link #feedHmdCandidate}（HMD 路径，ClientHmdState 扫描候选喂入）。</p>
 */
public final class RVP_IrSeekerTracker {

    private RVP_IrSeekerTracker() {}

    /** 同 tick 防重入（mixin tickFor / HMD feed / tick 维护可能同 tick 多路到达）。 */
    private static final Map<String, Long> LAST_SCAN_TICK = new HashMap<>();

    /**
     * 全局维护（每客户端 tick）：玩家不在载具 → 清全表；否则对当前操作站做上下文失效清理
     * （关导引头等）与 ARMED 保活（存活 + hold 包线 + 伺服 + 超时掉锁）。
     * 注意：扫描/dwell 不在这里做——非 HMD 由 mixin 调 {@link #tickFor}，HMD 由
     * ClientHmdState 调 {@link #feedHmdCandidate}，两路都带同 tick 防重入守卫。
     */
    public static void tick(Minecraft mc) {
        WeaponUnit unit = LocalVehiclePlayer.instance.onVehicle()
                ? LocalVehiclePlayer.instance.getWeaponUnit()
                : null;
        if (unit == null || unit.getVehicle() == null) {
            if (!RVP_IrSeekerStateTable.isAllEmpty()) {
                // 离开载具：先解除仍 armed 的站级锁（只清自己写的）并发解锁包，再清表。
                forEachArmed((u, state) -> unarm(u, state, "vehicle-left"));
                RVP_IrSeekerStateTable.clearAll();
            }
            LAST_SCAN_TICK.clear();
            return;
        }
        // 上下文失效（关导引头等）→ 按方案重置并解除自己写的站级锁。
        resetIfContextBroken(unit);
        // ARMED 保活：存活 + hold 包线（含离轴/距离/高度/LOS）+ 伺服跟随 + 超时掉锁。
        maintainArmed(unit);
    }

    /**
     * 非 HMD IR 路径入口（mixin IR 分支一行转发）：扫描候选 → dwell 累积 → 满则 armed。
     * HMD 武器不在此扫描（HMD 自有扫描经 {@link #feedHmdCandidate} 喂入，防双通道抢目标）。
     */
    public static void tickFor(WeaponUnit unit, RVP_WeaponBase rvpWeapon) {
        RVP_WeaponData data = rvpWeapon.getData();
        if (data.isEnableIrHmd()) {
            return;
        }
        ResourceLocation weaponId = data.getWeaponId();
        RVP_IrSeekerStateTable.State st = RVP_IrSeekerStateTable.stateFor(unit, weaponId);
        if (st.weaponId != weaponId) {
            RVP_IrSeekerStateTable.reset(unit, weaponId);
            st = RVP_IrSeekerStateTable.stateFor(unit, weaponId);
        }
        if (alreadyScannedThisTick(unit, weaponId)) {
            return;
        }
        long gameTime = unit.getVehicle().level().getGameTime();
        markScannedThisTick(unit, weaponId, gameTime);
        int dwell = RVP_GuidanceDataIR.resolveLockDwellTick(data.getGuidanceData());
        int grace = RVP_GuidanceDataIR.resolveDwellGraceTick(data.getGuidanceData());

        // 候选粘滞：当前候选仍存活且在包线内 → 不重扫（防伺服跟随导致左右横跳）。
        Entity best = null;
        Entity sticky = st.candidateId == -1 ? null
                : unit.getVehicle().level().getEntity(st.candidateId);
        if (sticky != null && sticky.isAlive()
                && RVP_IrLockHelper.isTargetWithinAcquireLimits(unit, sticky, data)) {
            best = sticky;
        } else {
            best = Infrared.findTarget(unit,
                    RVP_IrLockHelper.halfAngleFromFull(data.resolveLaunchSeekerFullFov()));
            if (best != null && !RVP_IrLockHelper.isTargetWithinAcquireLimits(unit, best, data)) {
                best = null;
            }
        }

        if (best == null) {
            if (st.armedId != -1) {
                return; // ARMED 保活由 tick(Minecraft) 统一处理。
            }
            if (gameTime - st.lastInEnvelopeTick > grace) {
                // 超过宽限仍无候选 → 清零重扫。
                st.dwellTicks = 0;
                st.candidateId = -1;
                st.phase = RVP_IrSeekerStateTable.Phase.SEARCH;
            } else if (st.phase == RVP_IrSeekerStateTable.Phase.OFF) {
                st.phase = RVP_IrSeekerStateTable.Phase.SEARCH;
            }
            return;
        }

        if (best.getId() != st.candidateId) {
            // 换候选（含新捕获）→ 驻留清零重算。
            st.candidateId = best.getId();
            st.dwellTicks = 0;
        }
        st.dwellTicks++;
        st.lastInEnvelopeTick = gameTime;
        st.phase = RVP_IrSeekerStateTable.Phase.TRACKING;
        // D1：追踪期即自动跟随（伺服），锁定延迟手感来源于 dwell。
        unit.aim(RVP_AimPointResolver.resolveLargestObbCenter(best));
        if (st.dwellTicks >= dwell && st.armedId == -1) {
            arm(unit, data, best, st);
        }
    }

    /**
     * HMD 路径喂入口：ClientHmdState 扫描得到候选后调用（替代原 confirmIrLock 直锁）。
     * dwell 满才由本类 arm（返回 true = 本 tick 发生 armed 跃迁，调用方可据此出提示）；
     * 未满时只累积（HMD 不出锁定视觉，方案 D2）。
     */
    public static boolean feedHmdCandidate(WeaponUnit unit, RVP_WeaponData data,
                                           Entity candidate, double effectiveAngleDeg) {
        ResourceLocation weaponId = data.getWeaponId();
        RVP_IrSeekerStateTable.State st = RVP_IrSeekerStateTable.stateFor(unit, weaponId);
        if (alreadyScannedThisTick(unit, weaponId)) {
            return false;
        }
        long gameTime = unit.getVehicle().level().getGameTime();
        markScannedThisTick(unit, weaponId, gameTime);
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
        // 喂入前先结算宽限：距上次在包线已超 G 的，旧进度作废重算（与空候选路径同口径）。
        if (st.candidateId != candidate.getId() && gameTime - st.lastInEnvelopeTick > grace) {
            st.dwellTicks = 0;
            st.candidateId = -1;
        }
        if (candidate.getId() != st.candidateId) {
            st.candidateId = candidate.getId();
            st.dwellTicks = 0;
        }
        st.dwellTicks++;
        st.lastInEnvelopeTick = gameTime;
        st.phase = RVP_IrSeekerStateTable.Phase.TRACKING;
        unit.aim(RVP_AimPointResolver.resolveLargestObbCenter(candidate));
        if (st.dwellTicks >= dwell && st.armedId == -1) {
            arm(unit, data, candidate, st);
            return true;
        }
        return false;
    }

    // ===================== 内部：arm / unarm / 维护 =====================

    /** 落锁：写站级锁（记 lastWrittenStationLockId）+ 发 C2SIrSeekerArmed + 置 ARMED。 */
    private static void arm(WeaponUnit unit, RVP_WeaponData data, Entity target,
                            RVP_IrSeekerStateTable.State st) {
        st.armedId = target.getId();
        st.lastArmedInEnvelopeTick = unit.getVehicle().level().getGameTime();
        st.phase = RVP_IrSeekerStateTable.Phase.ARMED;
        unit.setLockedEntity(target);
        st.lastWrittenStationLockId = target.getId();
        // HMD 弹：同步 HMD 通道视图（HUD 的 irLockedEntityId 保持一致）。
        if (data.isEnableIrHmd()) {
            RVP_ClientHmdState.getInstance().noteTrackerIrArm(target);
        }
        // 跃迁才发包（sendArmedUpdate，步骤 9 接线服务端表）。
        sendArmedUpdate(unit, data.getWeaponId(), target.getId(), true);
    }

    /** 掉锁：只清自己写的站级锁 + 发解锁包 + 整状态重置（ARMED → OFF）。 */
    private static void unarm(WeaponUnit unit, RVP_IrSeekerStateTable.State st, String reason) {
        if (st.lastWrittenStationLockId != -1) {
            Entity stationLock = unit.getLockedEntity();
            if (stationLock != null && stationLock.getId() == st.lastWrittenStationLockId) {
                unit.setLockedEntity(null);
            }
            st.lastWrittenStationLockId = -1;
        }
        if (st.armedId != -1) {
            sendArmedUpdate(unit, st.weaponId, -1, false);
            RVP_ClientHmdState.getInstance().noteTrackerIrUnarm(st.armedId);
            org.ywzj.rvp.client.debug.RVP_DebugStateLogs.logIrHms("ir-seeker unarm reason=" + reason + " target=" + st.armedId);
        }
        st.armedId = -1;
        st.candidateId = -1;
        st.dwellTicks = 0;
        st.phase = RVP_IrSeekerStateTable.Phase.OFF;
    }

    /** 上下文失效（关导引头等）→ 解除自己写的锁并清状态。 */
    private static void resetIfContextBroken(WeaponUnit unit) {
        for (Map.Entry<ResourceLocation, RVP_IrSeekerStateTable.State> entry
                : RVP_IrSeekerStateTable.snapshot(unit).entrySet()) {
            RVP_IrSeekerStateTable.State st = entry.getValue();
            boolean armed = st.armedId != -1;
            boolean broken = !unit.isSeekerOn();
            if (armed && broken) {
                unarm(unit, st, "context-broken");
            }
            if (broken) {
                RVP_IrSeekerStateTable.reset(unit, entry.getKey());
            }
        }
    }

    /** ARMED 保活：存活 + hold 包线 + 伺服 + 超时掉锁（hold 包线 = isTargetWithinHoldLimits）。 */
    private static void maintainArmed(WeaponUnit unit) {
        for (Map.Entry<ResourceLocation, RVP_IrSeekerStateTable.State> entry
                : RVP_IrSeekerStateTable.snapshot(unit).entrySet()) {
            RVP_IrSeekerStateTable.State st = entry.getValue();
            if (st.armedId == -1) {
                continue;
            }
            Entity target = unit.getVehicle().level().getEntity(st.armedId);
            if (target == null || !target.isAlive()) {
                unarm(unit, st, "target-gone");
                continue;
            }
            RVP_WeaponBase rvpWeapon = resolveRvpWeapon(unit, entry.getKey());
            if (rvpWeapon == null) {
                unarm(unit, st, "weapon-gone");
                continue;
            }
            RVP_WeaponData data = rvpWeapon.getData();
            if (RVP_IrLockHelper.isTargetWithinHoldLimits(unit, target, data)) {
                st.lastArmedInEnvelopeTick = unit.getVehicle().level().getGameTime();
                unit.aim(RVP_AimPointResolver.resolveLargestObbCenter(target));
            } else if (unit.getVehicle().level().getGameTime() - st.lastArmedInEnvelopeTick
                    > RVP_GuidanceDataIR.resolveHoldGraceTick(data.getGuidanceData())) {
                unarm(unit, st, "hold-expired");
            }
        }
    }

    /** 按 weaponId 解析当前站上的 RVP 武器；不在站上返回 null。 */
    private static RVP_WeaponBase resolveRvpWeapon(WeaponUnit unit, ResourceLocation weaponId) {
        return unit.getIndexedWeapons().stream()
                .filter(w -> w instanceof RVP_WeaponBase)
                .map(w -> (RVP_WeaponBase) w)
                .filter(w -> weaponId.equals(w.getData().getWeaponId()))
                .findFirst().orElse(null);
    }

    private static boolean alreadyScannedThisTick(WeaponUnit unit, ResourceLocation weaponId) {
        Long last = LAST_SCAN_TICK.get(scanKey(unit, weaponId));
        long now = unit.getVehicle() != null ? unit.getVehicle().level().getGameTime() : Long.MIN_VALUE;
        return last != null && last == now;
    }

    private static void markScannedThisTick(WeaponUnit unit, ResourceLocation weaponId, long gameTime) {
        LAST_SCAN_TICK.put(scanKey(unit, weaponId), gameTime);
    }

    private static String scanKey(WeaponUnit unit, ResourceLocation weaponId) {
        return unit.toString() + "@" + weaponId;
    }

    /**
     * C2SIrSeekerArmed 发送（步骤 9 接线：网络包 + 服务端 armed 表；步骤 4 阶段为空实现，
     * 仅保证客户端状态机可独立编译与冒烟）。
     */
    private static void sendArmedUpdate(WeaponUnit unit, ResourceLocation weaponId,
                                        int entityId, boolean armed) {
        // 步骤 9 接线点。
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
