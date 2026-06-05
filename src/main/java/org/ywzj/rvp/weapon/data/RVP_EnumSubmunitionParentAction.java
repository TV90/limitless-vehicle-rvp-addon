package org.ywzj.rvp.weapon.data;

import java.util.Locale;

/**
 * What happens to the parent projectile after a release wave completes.
 */
public enum RVP_EnumSubmunitionParentAction {

    /** Parent keeps flying (multi-stage rocket, APFSDS sabots). */
    CONTINUE,

    /** Parent is discarded after this release finishes all scheduled spawns (MCH cluster bomb). */
    DISCARD_AFTER_RELEASE,

    /** Parent is discarded immediately when the first spawn of this release fires. */
    DISCARD_ON_FIRST_SPAWN;

    public static RVP_EnumSubmunitionParentAction fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONTINUE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "discard_after_release", "discard_parent", "discard" -> DISCARD_AFTER_RELEASE;
            case "discard_on_first_spawn", "discard_immediate" -> DISCARD_ON_FIRST_SPAWN;
            default -> CONTINUE;
        };
    }
}
