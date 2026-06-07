package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.List;

/**
 * Per-stage rigidity window: {@code rigidity_time} ticks after a stage is entered, steering is suppressed.
 */
public final class RVP_GuidanceRigidityUtil {

    private RVP_GuidanceRigidityUtil() {}

    public static boolean isRigid(RVP_BaseBullet projectile, RVP_GuidanceContext context) {
        int rigidity = context.effective().steering().getRigidityTime();
        if (rigidity <= 0) {
            return false;
        }
        int entered = projectile.getGuidanceStageEnteredTick(context.stageIndex());
        return projectile.tickCount - entered < rigidity;
    }

    public static boolean isAnyActiveStageRigid(RVP_BaseBullet projectile, RVP_WeaponData data) {
        List<RVP_GuidancePhaseSelector.StageSelection> active =
                RVP_GuidancePhaseSelector.selectActive(projectile, data);
        for (RVP_GuidancePhaseSelector.StageSelection selection : active) {
            RVP_GuidanceStageData stage = selection.stage();
            int rigidity = RVP_GuidanceConfigMerger.forStage(stage).steering().getRigidityTime();
            if (rigidity <= 0) {
                continue;
            }
            int entered = projectile.getGuidanceStageEnteredTick(selection.index());
            if (projectile.tickCount - entered < rigidity) {
                return true;
            }
        }
        return false;
    }
}
