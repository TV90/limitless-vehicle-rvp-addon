package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_GuidanceDataHITL;
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
        return data.getGuidanceData().getGuidanceType() == RVP_EnumGuidanceType.HITL_TV
                ? Math.max(data.getGuidanceData().getMaxLockHalfAngle(), 1f)
                : 20f;
    }

    public static float resolveMaxLookOffsetDeg(RVP_WeaponData data) {
        if (data == null || !(data.getGuidanceData() instanceof RVP_GuidanceDataHITL hitl)) {
            return 20f;
        }
        return Math.max(hitl.getHitlMaxLookOffset(), 1f);
    }
}
