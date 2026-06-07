package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * Immutable input for pluggable guidance sources and stage selection.
 */
public record RVP_GuidanceContext(
        RVP_BaseBullet projectile,
        RVP_WeaponData data,
        RVP_GuidanceStageData stage,
        int stageIndex,
        RVP_GuidanceEffectiveConfig effective
) {
    public RVP_GuidanceContext withEffective(RVP_GuidanceEffectiveConfig config) {
        return new RVP_GuidanceContext(projectile, data, stage, stageIndex, config);
    }

    public RVP_GuidanceContext withSource(RVP_GuidanceData.Source source) {
        RVP_GuidanceEffectiveConfig merged = RVP_GuidanceConfigMerger.forSource(
                stage, source, source.getType());
        return withEffective(merged);
    }
}
