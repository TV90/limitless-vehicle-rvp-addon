package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSeekerData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSteeringData;

/**
 * Merges stage and source guidance parameters.
 */
public final class RVP_GuidanceConfigMerger {

    private RVP_GuidanceConfigMerger() {}

    public static RVP_GuidanceEffectiveConfig forStage(RVP_GuidanceStageData stage) {
        RVP_GuidanceSteeringData steering = mergeSteering(stage.getSteeringData(), null);
        RVP_GuidanceSeekerData seeker = stage.getSeeker().copy();
        return new RVP_GuidanceEffectiveConfig(steering, seeker, RVP_EnumGuidanceType.NONE);
    }

    public static RVP_GuidanceEffectiveConfig forSource(
            RVP_GuidanceStageData stage,
            RVP_GuidanceData.Source source,
            RVP_EnumGuidanceType activeType
    ) {
        RVP_GuidanceSteeringData steering = mergeSteering(stage.getSteeringData(), source.getSteeringData());
        RVP_GuidanceSeekerData seeker = stage.getSeeker().copy();
        return new RVP_GuidanceEffectiveConfig(steering, seeker, activeType);
    }

    public static RVP_GuidanceSteeringData mergeSteering(
            RVP_GuidanceSteeringData stage,
            RVP_GuidanceSteeringData source
    ) {
        RVP_GuidanceSteeringData merged = new RVP_GuidanceSteeringData();
        if (stage != null) {
            merged.applyOverride(stage);
        }
        if (source != null) {
            merged.applyOverride(source);
        }
        return merged;
    }
}
