package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates stage selection, source compositing and steering each tick.
 */
public final class RVP_GuidanceController {

    private RVP_GuidanceController() {}

    public static void tick(RVP_BaseBullet projectile) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || data.getGuidanceData().getStages().isEmpty()) {
            return;
        }

        List<RVP_GuidancePhaseSelector.StageSelection> active =
                RVP_GuidancePhaseSelector.selectActive(projectile, data);
        if (active.isEmpty()) {
            return;
        }

        if (active.size() == 1) {
            applyStage(projectile, data, active.get(0));
            return;
        }

        applyOverlappingStages(projectile, data, active);
    }

    private static void applyStage(
            RVP_BaseBullet projectile,
            RVP_WeaponData data,
            RVP_GuidancePhaseSelector.StageSelection selection
    ) {
        RVP_GuidanceStageData stage = selection.stage();
        RVP_GuidanceEffectiveConfig stageConfig = RVP_GuidanceConfigMerger.forStage(stage);
        RVP_GuidanceContext context = new RVP_GuidanceContext(
                projectile, data, stage, selection.index(), stageConfig);

        List<RVP_GuidanceData.Source> sources = stage.getSources().stream()
                .sorted(Comparator.comparingInt(RVP_GuidanceData.Source::getPriority).reversed())
                .toList();
        RVP_GuidanceCompositor.applyStage(context, sources);
    }

    private static void applyOverlappingStages(
            RVP_BaseBullet projectile,
            RVP_WeaponData data,
            List<RVP_GuidancePhaseSelector.StageSelection> active
    ) {
        List<RVP_GuidanceCompositor.PhaseBlend> merged = new ArrayList<>();
        for (RVP_GuidancePhaseSelector.StageSelection selection : active) {
            RVP_GuidanceStageData stage = selection.stage();
            RVP_GuidanceEffectiveConfig stageConfig = RVP_GuidanceConfigMerger.forStage(stage);
            RVP_GuidanceContext baseContext = new RVP_GuidanceContext(
                    projectile, data, stage, selection.index(), stageConfig);
            List<RVP_GuidanceData.Source> sources = stage.getSources().stream()
                    .sorted(Comparator.comparingInt(RVP_GuidanceData.Source::getPriority).reversed())
                    .toList();
            merged.add(new RVP_GuidanceCompositor.PhaseBlend(
                    baseContext, sources, stage.getCompositeWeight()));
        }
        RVP_GuidanceCompositor.applyOverlappingPhases(projectile, merged);
    }
}
