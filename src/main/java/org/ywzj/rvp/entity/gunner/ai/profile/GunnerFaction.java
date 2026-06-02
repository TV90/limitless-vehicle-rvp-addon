package org.ywzj.rvp.entity.gunner.ai.profile;

import java.util.Locale;

public enum GunnerFaction {
    FRIENDLY,
    ENEMY,
    TEAM;

    public static GunnerFaction parse(String value) {
        if (value == null) {
            return FRIENDLY;
        }
        String v = value.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "enemy", "hostile" -> ENEMY;
            case "team", "faction" -> TEAM;
            default -> FRIENDLY;
        };
    }
}
