package org.ywzj.rvp.client.state;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.lead.RVP_ClientMachinegunLeadResolver;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class RVP_MachinegunLeadState {
    /** 各武器站的解算、世界坐标 EMA 与渲染插值状态，键由载具实体 ID 和武器站索引组合。 */
    private static final Map<Integer, State> STATES = new HashMap<>();

    /** 原始解短暂缺失时继续显示上一份解的最长时间，单位为 tick。 */
    private static final int RAW_MISS_HOLD_TICKS = 8;

    /** 世界提前点 EMA 的最低插值系数。 */
    private static final double LEAD_ALPHA_BASE = 0.23D;

    /** 世界提前点误差对 EMA 插值系数的增益。 */
    private static final double LEAD_ALPHA_SCALE = 0.022D;

    /** 世界提前点 EMA 允许使用的最大插值系数。 */
    private static final double LEAD_ALPHA_MAX = 0.46D;

    /**
     * EMA 理论相位滞后的补偿比例；补足全部滞后后额外前置 ，使匀速火控宁可略提前而不落后。
     */
    private static final double LEAD_PHASE_COMPENSATION_RATIO = 1.15D;

    private RVP_MachinegunLeadState() {}

    /**
     * 取得当前武器站本 tick 的 EMA 与相位补偿提前量解。
     *
     * <p>同一武器站在同一个载具 tick 内只允许调用一次实际弹道解算；火控执行器和 HUD 后续调用
     * 直接复用状态中的结果，避免解算成本随帧率或鼠标采样率增长。世界提前点保留 EMA 抗抖，
     * 再按当前 {@code alpha} 的理论滞后 tick 补偿 1+LEAD_PHASE_COMPENSATION_RATIO，使高速匀速目标略微提前而不拖尾。</p>
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
            // 调用本项目客户端机炮追踪解析器，每个武器站每 tick 仅生成一份原始物理解供 EMA、火控与 HUD 共用。
            acceptRawSolution(weaponUnit, state,
                    RVP_ClientMachinegunLeadResolver.solveCurrent(weaponUnit, 1.0F), nowTick);
        }
        prune(nowTick);
        return state.controlSolution;
    }

    /**
     * 取得 HUD 使用的帧间插值解。
     *
     * <p>这里只在相邻两个已经过 EMA 与相位补偿的控制解之间按渲染 partialTick 插值，
     * 保证 HUD 与实际火控使用同一世界坐标控制点。</p>
     */
    @Nullable
    public static RVP_LeadSolution resolveDisplayCurrent(WeaponUnit weaponUnit, float partialTick) {
        RVP_LeadSolution control = resolveCurrent(weaponUnit, 1.0F);
        State state = STATES.get(key(weaponUnit));
        if (state == null || !state.initialized) {
            return null;
        }
        int nowTick = weaponUnit.getVehicle().tickCount;
        if (control == null && nowTick - state.lastSeenTick > RAW_MISS_HOLD_TICKS) {
            state.initialized = false;
            return null;
        }
        if (control == null || state.previousControlSolution == null) {
            return state.currentControlSolution;
        }
        return interpolateSolution(state.previousControlSolution, state.currentControlSolution, partialTick);
    }

    /** 把本 tick 的原始解写入缓存；空解只触发 HUD 短时保持，不刷新有效时间。 */
    private static void acceptRawSolution(WeaponUnit weaponUnit, State state,
                                          @Nullable RVP_LeadSolution raw, int nowTick) {
        if (raw == null) {
            state.controlSolution = null;
            return;
        }

        String weaponKey = currentWeaponKey(weaponUnit);
        int targetId = raw.target() == null ? -1 : raw.target().getId();
        if (!state.initialized
                || state.targetId != targetId
                || !weaponKey.equals(state.weaponKey)
                || nowTick - state.lastSeenTick != 1) {
            state.smoothedLead = raw.leadWorldPos();
            state.previousControlSolution = raw;
            state.currentControlSolution = raw;
            state.weaponKey = weaponKey;
            state.targetId = targetId;
            state.initialized = true;
        } else if (state.lastSeenTick != nowTick) {
            state.previousControlSolution = state.currentControlSolution;

            double leadError = state.smoothedLead.distanceTo(raw.leadWorldPos());
            double leadAlpha = Mth.clamp(
                    LEAD_ALPHA_BASE + leadError * LEAD_ALPHA_SCALE,
                    LEAD_ALPHA_BASE,
                    LEAD_ALPHA_MAX
            );
            Vec3 previousSmoothedLead = state.smoothedLead;
            state.smoothedLead = previousSmoothedLead.lerp(raw.leadWorldPos(), leadAlpha);
            Vec3 compensatedLead = compensateSmoothedLead(
                    previousSmoothedLead, state.smoothedLead, leadAlpha);
            state.currentControlSolution = withLeadWorldPos(raw, compensatedLead);
        }
        state.controlSolution = state.currentControlSolution;
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

    /** 在两份相邻的原始物理解之间做纯渲染插值。 */
    static RVP_LeadSolution interpolateSolution(RVP_LeadSolution previous,
                                                RVP_LeadSolution current,
                                                float partialTick) {
        float clampedPartial = Mth.clamp(partialTick, 0f, 1f);
        Vec3 renderLead = previous.leadWorldPos().lerp(current.leadWorldPos(), clampedPartial);
        Vec3 renderTarget = previous.targetWorldPos().lerp(current.targetWorldPos(), clampedPartial);
        double renderTime = Mth.lerp(clampedPartial, (float) previous.timeToImpact(), (float) current.timeToImpact());
        double renderMiss = Mth.lerp(clampedPartial, (float) previous.missDistance(), (float) current.missDistance());
        double renderTravel = Mth.lerp(
                clampedPartial,
                (float) previous.projectileTravelDistanceMeters(),
                (float) current.projectileTravelDistanceMeters()
        );
        return new RVP_LeadSolution(
                current.target(), renderTarget, renderLead, renderTime, renderMiss, renderTravel);
    }

    /**
     * 按 EMA 的理论稳态滞后 tick 对当前平滑点直接做部分相位补偿。
     *
     * <p>理论滞后为 {@code (1-alpha)/alpha} tick；补偿 LEAD_PHASE_COMPENSATION_RATIO 后，匀速目标会额外前置原 EMA
     * 滞后的 LEAD_PHASE_COMPENSATION_RATIO。补偿仍随世界提前点速度变化，不产生固定米数或目标朝向偏置。</p>
     */
    static Vec3 compensateSmoothedLead(Vec3 previousSmoothedLead, Vec3 currentSmoothedLead, double alpha) {
        Vec3 safePrevious = previousSmoothedLead == null ? Vec3.ZERO : previousSmoothedLead;
        Vec3 safeCurrent = currentSmoothedLead == null ? safePrevious : currentSmoothedLead;
        double safeAlpha = Mth.clamp(alpha, 1.0E-4D, 1.0D);
        double compensationTicks = (1.0D - safeAlpha) / safeAlpha * LEAD_PHASE_COMPENSATION_RATIO;
        Vec3 smoothedVelocity = safeCurrent.subtract(safePrevious);
        return safeCurrent.add(smoothedVelocity.scale(compensationTicks));
    }

    /** 用补偿后的世界提前点构造控制解，其余弹道物理量保持本 tick 原始值。 */
    private static RVP_LeadSolution withLeadWorldPos(RVP_LeadSolution raw, Vec3 leadWorldPos) {
        return new RVP_LeadSolution(
                raw.target(),
                raw.targetWorldPos(),
                leadWorldPos,
                raw.timeToImpact(),
                raw.missDistance(),
                raw.projectileTravelDistanceMeters()
        );
    }

    private static final class State {
        /** 本 tick 供火控直接使用的 EMA 相位补偿解；空值表示本 tick 没有有效锁定。 */
        @Nullable
        RVP_LeadSolution controlSolution;

        /** 上一 tick 的 EMA 相位补偿解，仅供 HUD 帧间插值。 */
        @Nullable
        RVP_LeadSolution previousControlSolution;

        /** 最近一份有效 EMA 相位补偿解，供 HUD 插值与短时显示保持。 */
        @Nullable
        RVP_LeadSolution currentControlSolution;

        /** 当前世界提前点的纯 EMA 状态，不包含相位补偿。 */
        Vec3 smoothedLead = Vec3.ZERO;

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
