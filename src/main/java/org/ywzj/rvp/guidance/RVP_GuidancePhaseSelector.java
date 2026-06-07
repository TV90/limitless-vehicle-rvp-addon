package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.activation.RVP_GuidanceActivationContext;
import org.ywzj.rvp.guidance.activation.RVP_GuidanceActivationEvaluator;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Selects all guidance stages active this tick. Overlapping stages imply composite guidance.
 */
public final class RVP_GuidancePhaseSelector {

    private RVP_GuidancePhaseSelector() {}

    public record StageSelection(RVP_GuidanceStageData stage, int index) {}

    public static List<StageSelection> selectActive(RVP_BaseBullet projectile, RVP_WeaponData data) {
        List<RVP_GuidanceStageData> stages = data.getGuidanceData().getStages();
        if (stages.isEmpty()) {
            return List.of();
        }

        RVP_GuidanceActivationContext ctx = RVP_GuidanceActivationContext.from(projectile);
        Set<Integer> sticky = projectile.getGuidanceStickyPhaseIndices();
        List<StageSelection> active = new ArrayList<>();

        for (int i = 0; i < stages.size(); i++) {
            RVP_GuidanceStageData stage = stages.get(i);
            RVP_GuidanceActivationData activation = stage.getActivation();
            boolean stickyHeld = sticky.contains(i);
            if (stickyHeld || RVP_GuidanceActivationEvaluator.isPhaseActive(activation, ctx)) {
                active.add(new StageSelection(stage, i));
                if (activation.isEnterOnce() && !stickyHeld) {
                    projectile.addGuidanceStickyPhaseIndex(i);
                }
            }
        }

        if (active.size() <= 1) {
            updatePrimaryIndex(projectile, active);
            return active;
        }

        List<StageSelection> resolved = RVP_GuidanceCompositeCompatibility.resolveCompatible(
                active,
                ss -> ss.stage().getActivation().specificityScore(),
                ss -> ss.stage().getPrimaryGuidanceType()
        );
        updatePrimaryIndex(projectile, resolved);
        return resolved;
    }

    private static void updatePrimaryIndex(RVP_BaseBullet projectile, List<StageSelection> active) {
        if (active.isEmpty()) {
            return;
        }
        int index = active.get(0).index();
        if (projectile.getGuidanceStageIndex() != index) {
            projectile.setGuidanceStageIndex(index);
            projectile.setGuidanceStageEnteredTick(projectile.tickCount);
        }
        String names = active.stream()
                .map(ss -> ss.stage().getName())
                .reduce((a, b) -> a + "+" + b)
                .orElse("");
        projectile.setActiveStageName(names);
    }
}
