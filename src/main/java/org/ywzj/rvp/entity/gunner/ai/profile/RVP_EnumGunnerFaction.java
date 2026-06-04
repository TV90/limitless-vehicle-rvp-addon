package org.ywzj.rvp.entity.gunner.ai.profile;

import java.util.Locale;

public enum RVP_EnumGunnerFaction {
    FRIENDLY,
    ENEMY,
    TEAM;

    public static RVP_EnumGunnerFaction parse(String value) {
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
