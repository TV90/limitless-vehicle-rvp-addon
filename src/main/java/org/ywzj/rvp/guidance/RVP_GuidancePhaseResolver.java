package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves multiple simultaneously active guidance stages according to {@link RVP_EnumPhaseResolvePolicy}.
 */
public final class RVP_GuidancePhaseResolver {

    private RVP_GuidancePhaseResolver() {}

    public static List<RVP_GuidancePhaseSelector.StageSelection> resolve(
            RVP_BaseBullet projectile,
            RVP_WeaponData data,
            List<RVP_GuidancePhaseSelector.StageSelection> active
    ) {
        if (active.size() <= 1) {
            return active;
        }
        RVP_GuidanceData guidance = data.getGuidanceData();
        return switch (guidance.getPhaseResolvePolicy()) {
            case FIRST_PHASE -> List.of(active.get(0));
            case STICKY -> resolveSticky(projectile, active);
            case HIGHEST_SPECIFICITY -> RVP_GuidanceCompositeCompatibility.resolveCompatible(
                    active,
                    ss -> ss.stage().getActivation().specificityScore(),
                    ss -> ss.stage().getPrimaryGuidanceType()
            );
        };
    }

    private static List<RVP_GuidancePhaseSelector.StageSelection> resolveSticky(
            RVP_BaseBullet projectile,
            List<RVP_GuidancePhaseSelector.StageSelection> active
    ) {
        int held = projectile.getGuidanceOverlapResolveIndex();
        if (held >= 0) {
            for (RVP_GuidancePhaseSelector.StageSelection selection : active) {
                if (selection.index() == held) {
                    return List.of(selection);
                }
            }
        }
        List<RVP_GuidancePhaseSelector.StageSelection> resolved =
                RVP_GuidanceCompositeCompatibility.resolveCompatible(
                        active,
                        ss -> ss.stage().getActivation().specificityScore(),
                        ss -> ss.stage().getPrimaryGuidanceType()
                );
        if (!resolved.isEmpty()) {
            projectile.setGuidanceOverlapResolveIndex(resolved.get(0).index());
        }
        return resolved.isEmpty() ? new ArrayList<>(active) : resolved;
    }
}
