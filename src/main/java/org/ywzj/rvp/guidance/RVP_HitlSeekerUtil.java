package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * HITL seeker FOV / look-offset limits from weapon guidance stages.
 */
public final class RVP_HitlSeekerUtil {

    private RVP_HitlSeekerUtil() {}

    public static float saclosSeekerHalfFov(RVP_WeaponData data) {
        if (data == null) {
            return 20f;
        }
        float half = 0f;
        for (RVP_GuidanceStageData stage : data.getGuidanceData().getStages()) {
            boolean saclos = stage.getSources().stream()
                    .anyMatch(source -> source.getType() == RVP_EnumGuidanceType.SACLOS);
            if (!saclos) {
                continue;
            }
            half = Math.max(half, stage.getSeeker().getFov() * 0.5f);
        }
        return half > 0f ? half : 20f;
    }

    public static float resolveMaxLookOffsetDeg(RVP_WeaponData data) {
        if (data == null || !data.hasHumanInTheLoop()) {
            return 20f;
        }
        return data.getGuidanceData().getHumanInTheLoop()
                .maxLookOffsetDeg(saclosSeekerHalfFov(data));
    }
}
