package org.ywzj.rvp.weapon.data;

import java.util.Locale;

/**
 * When a {@link RVP_SubmunitionReleaseData} wave may fire.
 */
public enum RVP_EnumSubmunitionTrigger {

    /** During flight; uses {@link RVP_SubmunitionReleaseData#getDelayTick()} / {@link RVP_SubmunitionReleaseData#getIntervalTick()}. */
    IN_FLIGHT,

    /** Any fatal impact (block or entity) before the parent is discarded. */
    ON_IMPACT,

    /** Block impact only (before explosion/detonate). */
    ON_BLOCK_HIT,

    /** Entity impact only (after penetration exhausted). */
    ON_ENTITY_HIT,

    /** Timed / proximity / airburst / life-end fuse detonation (before parent explosion). */
    ON_FUSE;

    public static RVP_EnumSubmunitionTrigger fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return IN_FLIGHT;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "in_flight", "flight", "air" -> IN_FLIGHT;
            case "on_impact", "impact" -> ON_IMPACT;
            case "on_block_hit", "block", "block_hit" -> ON_BLOCK_HIT;
            case "on_entity_hit", "entity", "entity_hit" -> ON_ENTITY_HIT;
            case "on_fuse", "fuse", "detonate" -> ON_FUSE;
            default -> IN_FLIGHT;
        };
    }
}
