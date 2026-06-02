package org.ywzj.rvp;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

public final class RvpHomingModes {
    public static final String ACTIVE_RADAR = "ACTIVE_RADAR";

    public static final String SEMI_ACTIVE_RADAR = "SEMI_ACTIVE_RADAR";

    public static final String ANTI_RADIATION = "ANTI_RADIATION";

    private RvpHomingModes() {
    }

    public static boolean isAntiRadiationHoming(String homingMode) {
        if (homingMode == null) {
            return false;
        }
        String mode = homingMode.trim();
        return ANTI_RADIATION.equalsIgnoreCase(mode)
                || "ANTI_RADAR".equalsIgnoreCase(mode);
    }

    public static void migrateLegacyHomingFields(JsonObject obj) {
        String homingMode = GsonHelper.getAsString(obj, "homing_mode", null);
        if (homingMode != null && "ANTI_RADAR".equalsIgnoreCase(homingMode)) {
            obj.addProperty("homing_mode", ANTI_RADIATION);
        }
        if (GsonHelper.getAsBoolean(obj, "anti_radar", false)) {
            obj.addProperty("anti_radiation", true);
            obj.remove("anti_radar");
        }
        if (GsonHelper.getAsBoolean(obj, "arm_preselect_enabled", false) && !obj.has("anti_radiation_preselect_enabled")) {
            obj.add("anti_radiation_preselect_enabled", obj.get("arm_preselect_enabled"));
        }
        migrateLegacyAntiRadiationField(obj, "arm_scan_interval_tick", "anti_radiation_scan_interval_tick");
        migrateLegacyAntiRadiationField(obj, "arm_memory_tick", "anti_radiation_memory_tick");
        migrateLegacyAntiRadiationField(obj, "arm_seek_range", "anti_radiation_seek_range");
        migrateLegacyAntiRadiationField(obj, "arm_allow_reacquire", "anti_radiation_allow_reacquire");
        migrateLegacyAntiRadiationField(obj, "arm_allow_fire_without_seeker", "anti_radiation_allow_fire_without_seeker");
        migrateLegacyAntiRadiationField(obj, "arm_radiation_pulse_memory_tick", "anti_radiation_radiation_pulse_memory_tick");
        migrateLegacyAntiRadiationField(obj, "arm_locked_bonus", "anti_radiation_locked_bonus");
        migrateLegacyAntiRadiationField(obj, "arm_preselect_enabled", "anti_radiation_preselect_enabled");
    }

    public static void migrateLegacyTvMissileFields(JsonObject obj) {
        migrateLegacyField(obj, "tv_control_range", "tv_missile_control_range");
        migrateLegacyField(obj, "tv_timeout_tick", "tv_missile_timeout_tick");
        if (obj.has("tv_video_modes") && !obj.has("tv_missile_video_modes")) {
            obj.add("tv_missile_video_modes", obj.get("tv_video_modes"));
        }
    }

    private static void migrateLegacyAntiRadiationField(JsonObject obj, String legacyKey, String newKey) {
        migrateLegacyField(obj, legacyKey, newKey);
    }

    private static void migrateLegacyField(JsonObject obj, String legacyKey, String newKey) {
        if (obj.has(legacyKey) && !obj.has(newKey)) {
            obj.add(newKey, obj.get(legacyKey));
        }
    }
}
