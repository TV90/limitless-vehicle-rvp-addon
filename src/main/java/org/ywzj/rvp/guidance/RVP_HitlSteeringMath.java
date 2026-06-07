package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;

/**
 * Rate-limited HITL MOUSE steering: command heading vs integrated body/seeker heading.
 */
public final class RVP_HitlSteeringMath {

    public static final float DEFAULT_MAX_TURN_DEG_PER_TICK = 3.5f;

    private RVP_HitlSteeringMath() {}

    public static float stepYawToward(float current, float target, float maxDegPerTick) {
        float delta = Mth.wrapDegrees(target - current);
        return Mth.wrapDegrees(current + Mth.clamp(delta, -maxDegPerTick, maxDegPerTick));
    }

    public static float stepPitchToward(float current, float target, float maxDegPerTick) {
        float delta = target - current;
        return Mth.clamp(current + Mth.clamp(delta, -maxDegPerTick, maxDegPerTick), -89.9f, 89.9f);
    }
}
