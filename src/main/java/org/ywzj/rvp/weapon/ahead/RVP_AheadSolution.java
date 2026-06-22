package org.ywzj.rvp.weapon.ahead;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public record RVP_AheadSolution(
        @Nullable Vec3 referenceWorldPos,
        double referenceDistanceMeters,
        int programmedDistanceMeters,
        boolean usedLeadSolution,
        @Nullable String invalidReason
) {
    public static RVP_AheadSolution invalid(String reason) {
        return new RVP_AheadSolution(null, 0.0D, 0, false, reason);
    }

    public boolean isValid() {
        return invalidReason == null && programmedDistanceMeters > 0;
    }
}
