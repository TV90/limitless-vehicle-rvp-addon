package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.ywzj.rvp.weapon.data.RVP_Range;

/** HUD profile derived from the new weapon-level IR lock altitude range. */
public enum RVP_IrHudProfile {
    AIR,
    GROUND,
    MIXED;

    public static RVP_IrHudProfile resolve(RVP_Range<Float> altitudeRange) {
        if (altitudeRange == null) {
            return AIR;
        }
        boolean allowsLow = altitudeRange.contains(0f);
        boolean allowsHigh = altitudeRange.contains(1000f);
        if (allowsLow && allowsHigh) {
            return MIXED;
        }
        return allowsLow ? GROUND : AIR;
    }

    public static RVP_IrHudProfile resolveForTarget(RVP_Range<Float> altitudeRange, Entity target) {
        if (altitudeRange == null || target == null || target.level() == null) {
            return resolve(altitudeRange);
        }
        int groundY = target.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(target.getX()),
                Mth.floor(target.getZ())
        );
        return target.getY() - groundY <= 25.0D ? GROUND : AIR;
    }
}
