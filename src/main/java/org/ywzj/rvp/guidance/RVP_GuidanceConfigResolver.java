package org.ywzj.rvp.guidance;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSeekerData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSteeringData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.List;

/**
 * Resolves steering/seeker snapshots for the active guidance stage, or launch-time fallbacks.
 */
public final class RVP_GuidanceConfigResolver {

    private RVP_GuidanceConfigResolver() {}

    @Nullable
    public static RVP_GuidanceStageData resolveReferenceStage(RVP_WeaponData data, @Nullable RVP_BaseBullet projectile) {
        if (data == null) {
            return null;
        }
        List<RVP_GuidanceStageData> stages = data.getGuidanceData().getStages();
        if (stages.isEmpty()) {
            return null;
        }
        if (projectile != null && projectile.getRvpData() == data) {
            List<RVP_GuidancePhaseSelector.StageSelection> active =
                    RVP_GuidancePhaseSelector.selectActive(projectile, data);
            if (!active.isEmpty()) {
                return active.get(0).stage();
            }
        }
        return stages.get(0);
    }

    public static RVP_GuidanceSteeringData resolveSteering(RVP_WeaponData data, @Nullable RVP_BaseBullet projectile) {
        RVP_GuidanceStageData stage = resolveReferenceStage(data, projectile);
        if (stage == null || stage.getSteeringData() == null) {
            return new RVP_GuidanceSteeringData();
        }
        RVP_GuidanceSteeringData merged = new RVP_GuidanceSteeringData();
        merged.applyOverride(stage.getSteeringData());
        return merged;
    }

    public static RVP_GuidanceSeekerData resolveSeeker(RVP_WeaponData data, @Nullable RVP_BaseBullet projectile) {
        RVP_GuidanceStageData stage = resolveReferenceStage(data, projectile);
        if (stage == null) {
            return new RVP_GuidanceSeekerData();
        }
        if (!stage.getSeeker().isEmpty()) {
            return stage.getSeeker().copy();
        }
        return data.getGuidanceData().getStages().stream()
                .map(RVP_GuidanceStageData::getSeeker)
                .filter(seeker -> !seeker.isEmpty())
                .findFirst()
                .map(RVP_GuidanceSeekerData::copy)
                .orElse(new RVP_GuidanceSeekerData());
    }

    public static RVP_GuidanceEffectiveConfig resolveEffective(
            RVP_WeaponData data,
            @Nullable RVP_BaseBullet projectile,
            RVP_EnumGuidanceType activeType
    ) {
        return new RVP_GuidanceEffectiveConfig(
                resolveSteering(data, projectile),
                resolveSeeker(data, projectile),
                activeType
        );
    }
}
