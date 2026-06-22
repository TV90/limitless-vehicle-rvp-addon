package org.ywzj.rvp.client.state;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class RVP_MachinegunLeadState {
    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final int RAW_MISS_HOLD_TICKS = 8;
    private static final double LEAD_ALPHA_BASE = 0.23D;
    private static final double LEAD_ALPHA_SCALE = 0.022D;
    private static final double LEAD_ALPHA_MAX = 0.46D;
    private static final double TARGET_ALPHA_BASE = 0.30D;
    private static final double TARGET_ALPHA_SCALE = 0.034D;
    private static final double TARGET_ALPHA_MAX = 0.66D;
    private static final double LEAD_FORWARD_COMPENSATION = 0.02D;
    private static final double TARGET_FORWARD_COMPENSATION = 0.01D;

    private RVP_MachinegunLeadState() {}

    @Nullable
    public static RVP_LeadSolution smooth(WeaponUnit weaponUnit, @Nullable RVP_LeadSolution raw, float partialTick) {
        int key = key(weaponUnit);
        int nowTick = weaponUnit.getVehicle().tickCount;
        if (raw == null) {
            State state = STATES.get(key);
            if (state == null) {
                return null;
            }
            if (nowTick - state.lastSeenTick > RAW_MISS_HOLD_TICKS) {
                STATES.remove(key);
                return null;
            }
            return buildSolution(state.target, state, partialTick);
        }

        State state = STATES.computeIfAbsent(key, unused -> new State());
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
            state.weaponKey = weaponKey;
            state.targetId = targetId;
            state.target = raw.target();
            state.initialized = true;
        } else if (state.lastSeenTick != nowTick) {
            state.prevLead = state.currLead;
            state.prevTarget = state.currTarget;
            state.prevTime = state.currTime;
            state.prevMiss = state.currMiss;

            double leadErr = state.currLead.distanceTo(raw.leadWorldPos());
            double targetErr = state.currTarget.distanceTo(raw.targetWorldPos());
            double leadAlpha = Mth.clamp(
                    LEAD_ALPHA_BASE + leadErr * LEAD_ALPHA_SCALE,
                    LEAD_ALPHA_BASE,
                    LEAD_ALPHA_MAX
            );
            double targetAlpha = Mth.clamp(
                    TARGET_ALPHA_BASE + targetErr * TARGET_ALPHA_SCALE,
                    TARGET_ALPHA_BASE,
                    TARGET_ALPHA_MAX
            );

            state.currLead = state.currLead.lerp(raw.leadWorldPos(), leadAlpha);
            state.currTarget = state.currTarget.lerp(raw.targetWorldPos(), targetAlpha);
            state.currTime += (raw.timeToImpact() - state.currTime) * leadAlpha;
            state.currMiss += (raw.missDistance() - state.currMiss) * leadAlpha;
            state.target = raw.target();
        }
        state.lastSeenTick = nowTick;
        prune(nowTick);
        return buildSolution(state.target, state, partialTick);
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
        // Keep a tiny forward compensation so the ring does not trail, but avoid the jumpy feel of aggressive feed-forward.
        Vec3 leadVelocity = state.currLead.subtract(state.prevLead);
        Vec3 targetVelocity = state.currTarget.subtract(state.prevTarget);
        renderLead = renderLead.add(leadVelocity.scale(LEAD_FORWARD_COMPENSATION));
        renderTarget = renderTarget.add(targetVelocity.scale(TARGET_FORWARD_COMPENSATION));
        double renderTime = Mth.lerp(clampedPartial, (float) state.prevTime, (float) state.currTime);
        double renderMiss = Mth.lerp(clampedPartial, (float) state.prevMiss, (float) state.currMiss);
        return new RVP_LeadSolution(target, renderTarget, renderLead, renderTime, renderMiss);
    }

    private static final class State {
        @Nullable
        Entity target;
        Vec3 prevLead = Vec3.ZERO;
        Vec3 currLead = Vec3.ZERO;
        Vec3 prevTarget = Vec3.ZERO;
        Vec3 currTarget = Vec3.ZERO;
        double prevTime;
        double currTime;
        double prevMiss;
        double currMiss;
        int lastSeenTick;
        int targetId = -1;
        String weaponKey = "";
        boolean initialized;
    }
}
