package org.ywzj.rvp.weapon.data;

import java.util.Locale;

/**
 * Payload spawned by {@link RVP_SubmunitionPayloadData}.
 */
public enum RVP_EnumSubmunitionPayloadKind {

    /** Another RVP weapon JSON ({@code rvp:<id>}). */
    RVP_WEAPON,

    /** Any registered {@link net.minecraft.world.entity.EntityType}. */
    ENTITY;

    public static RVP_EnumSubmunitionPayloadKind fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return RVP_WEAPON;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "entity", "minecraft_entity", "mob" -> ENTITY;
            default -> RVP_WEAPON;
        };
    }
}
