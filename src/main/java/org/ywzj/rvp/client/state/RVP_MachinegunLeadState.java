package org.ywzj.rvp.client.state;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class RVP_MachinegunLeadState {
    /** 各武器站的解算与平滑状态，键由载具实体 ID 和武器站索引组合。 */
    private static final Map<Integer, State> STATES = new HashMap<>();

    /** 原始解短暂缺失时继续显示上一份解的最长时间，单位为 tick。 */
    private static final int RAW_MISS_HOLD_TICKS = 8;

    /** 预瞄点平滑的最低插值系数。 */
    private static final double LEAD_ALPHA_BASE = 0.23D;

    /** 预瞄点误差对插值系数的增益。 */
    private static final double LEAD_ALPHA_SCALE = 0.022D;

    /** 预瞄点平滑允许使用的最大插值系数。 */
    private static final double LEAD_ALPHA_MAX = 0.46D;

    /** 预瞄点渲染前馈系数，用于抵消平滑产生的轻微拖尾。 */
    private static final double LEAD_FORWARD_COMPENSATION = 0.02D;

    private RVP_MachinegunLeadState() {}

    /**
     * 取得当前武器站的平滑提前量解。
     *
     * <p>同一武器站在同一个载具 tick 内只允许调用一次实际弹道解算；火控执行器和 HUD 后续调用
     * 直接复用状态中的结果，避免解算成本随帧率或鼠标采样率增长。</p>
     */
    @Nullable
    public static RVP_LeadSolution resolveCurrent(WeaponUnit weaponUnit, float partialTick) {
        if (weaponUnit == null) {
            return null;
        }
        int key = key(weaponUnit);
        int nowTick = weaponUnit.getVehicle().tickCount;
        State state = STATES.computeIfAbsent(key, unused -> new State());
        if (state.lastSolveTick != nowTick) {
            state.lastSolveTick = nowTick;
            // 调用本项目机炮解算器，每个武器站每 tick 仅生成一份原始解供火控与 HUD 共用。
            acceptRawSolution(weaponUnit, state,
                    RVP_MachinegunLeadSolver.solveCurrent(weaponUnit, 1.0F), nowTick);
        }

        if (!state.initialized) {
            return null;
        }
        if (nowTick - state.lastSeenTick > RAW_MISS_HOLD_TICKS) {
            // 保留本 tick 的空解缓存，防止火控与 HUD 在同一 tick 内因删除状态而各自重算一次。
            state.initialized = false;
            return null;
        }
        prune(nowTick);
        return buildSolution(state.target, state, partialTick);
    }

    /** 把本 tick 的原始解写入平滑状态；空解只触发既有短时保持，不刷新有效时间。 */
    private static void acceptRawSolution(WeaponUnit weaponUnit, State state,
                                          @Nullable RVP_LeadSolution raw, int nowTick) {
        if (raw == null) {
            return;
        }

        String weaponKey = currentWeaponKey(weaponUnit);
        int targetId = raw.target() == null ? -1 : raw.target().getId();
        if (!state.initialized
                || state.targetId != targetId
                || !weaponKey.equals(state.weaponKey)
                || nowTick - state.lastSeenTick > 8) {
            state.prevLead = raw.leadWorldPos();
            state.currLead = raw.leadWorldPos();
            state.prevTarget = raw.targetWorldPos();
            state.currTarget = raw.targetWorldPos();
            state.prevTime = raw.timeToImpact();
            state.currTime = raw.timeToImpact();
            state.prevMiss = raw.missDistance();
            state.currMiss = raw.missDistance();
            state.prevTravel = raw.projectileTravelDistanceMeters();
            state.currTravel = raw.projectileTravelDistanceMeters();
            state.weaponKey = weaponKey;
            state.targetId = targetId;
            state.target = raw.target();
            state.initialized = true;
        } else if (state.lastSeenTick != nowTick) {
            state.prevLead = state.currLead;
            state.prevTarget = state.currTarget;
            state.prevTime = state.currTime;
            state.prevMiss = state.currMiss;
            state.prevTravel = state.currTravel;

            double leadErr = state.currLead.distanceTo(raw.leadWorldPos());
            double leadAlpha = Mth.clamp(
                    LEAD_ALPHA_BASE + leadErr * LEAD_ALPHA_SCALE,
                    LEAD_ALPHA_BASE,
                    LEAD_ALPHA_MAX
            );

            state.currLead = state.currLead.lerp(raw.leadWorldPos(), leadAlpha);
            // 目标锚点必须保持真实碰撞箱中心，只做帧间插值，不做滞后平滑，否则虚线会脱离锁定框。
            state.currTarget = raw.targetWorldPos();
            state.currTime += (raw.timeToImpact() - state.currTime) * leadAlpha;
            state.currMiss += (raw.missDistance() - state.currMiss) * leadAlpha;
            state.currTravel += (raw.projectileTravelDistanceMeters() - state.currTravel) * leadAlpha;
            state.target = raw.target();
        }
        state.lastSeenTick = nowTick;
    }

    public static void clear(WeaponUnit weaponUnit) {
        STATES.remove(key(weaponUnit));
    }

    private static int key(WeaponUnit weaponUnit) {
        return weaponUnit.getVehicle().getId() * 257 + weaponUnit.getIndex();
    }

    private static String currentWeaponKey(WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().orElse(null);
        if (currentWeapon != null && currentWeapon.getData() instanceof RVP_WeaponData data && data.getWeaponId() != null) {
            return data.getWeaponId().toString();
        }
        return "";
    }

    private static void prune(int nowTick) {
        if (STATES.size() <= 24 || (nowTick & 31) != 0) {
            return;
        }
        Iterator<Map.Entry<Integer, State>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, State> entry = iterator.next();
            if (nowTick - entry.getValue().lastSeenTick > 80) {
                iterator.remove();
            }
        }
    }

    private static RVP_LeadSolution buildSolution(@Nullable Entity target, State state, float partialTick) {
        float clampedPartial = Mth.clamp(partialTick, 0f, 1f);
        Vec3 renderLead = state.prevLead.lerp(state.currLead, clampedPartial);
        Vec3 renderTarget = state.prevTarget.lerp(state.currTarget, clampedPartial);
        // 为预瞄圈保留极小前馈以抵消平滑拖尾，同时避免激进外推造成跳动。
        Vec3 leadVelocity = state.currLead.subtract(state.prevLead);
        renderLead = renderLead.add(leadVelocity.scale(LEAD_FORWARD_COMPENSATION));
        double renderTime = Mth.lerp(clampedPartial, (float) state.prevTime, (float) state.currTime);
        double renderMiss = Mth.lerp(clampedPartial, (float) state.prevMiss, (float) state.currMiss);
        double renderTravel = Mth.lerp(clampedPartial, (float) state.prevTravel, (float) state.currTravel);
        return new RVP_LeadSolution(target, renderTarget, renderLead, renderTime, renderMiss, renderTravel);
    }

    private static final class State {
        /** 当前解对应的锁定实体；仅用于构造对外解算结果。 */
        @Nullable
        Entity target;

        /** 上一 tick 的平滑预瞄点。 */
        Vec3 prevLead = Vec3.ZERO;

        /** 当前 tick 的平滑预瞄点。 */
        Vec3 currLead = Vec3.ZERO;

        /** 上一 tick 的真实目标中心锚点。 */
        Vec3 prevTarget = Vec3.ZERO;

        /** 当前 tick 的真实目标中心锚点。 */
        Vec3 currTarget = Vec3.ZERO;

        /** 上一 tick 的预测命中时间。 */
        double prevTime;

        /** 当前 tick 的预测命中时间。 */
        double currTime;

        /** 上一 tick 的预测脱靶距离。 */
        double prevMiss;

        /** 当前 tick 的预测脱靶距离。 */
        double currMiss;

        /** 上一 tick 的预测弹道累计距离。 */
        double prevTravel;

        /** 当前 tick 的预测弹道累计距离。 */
        double currTravel;

        /** 最近一次取得有效原始解的载具 tick。 */
        int lastSeenTick;

        /** 最近一次执行实际弹道解算的载具 tick。 */
        int lastSolveTick = Integer.MIN_VALUE;

        /** 当前解对应的目标实体 ID。 */
        int targetId = -1;

        /** 当前解对应的武器资源键。 */
        String weaponKey = "";

        /** 平滑状态是否已经取得过有效原始解。 */
        boolean initialized;
    }
}
