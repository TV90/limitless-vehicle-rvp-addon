package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;

import java.util.List;
import java.util.Locale;

public class VehicleTVMissileWeaponData extends VehicleMissileWeaponData {

    public static final int TV_MISSILE_MODE_COLOR = 1;
    public static final int TV_MISSILE_MODE_BW = 1 << 1;
    public static final int TV_MISSILE_MODE_THERMAL = 1 << 2;
    private static final int TV_MISSILE_MODE_ALL = TV_MISSILE_MODE_COLOR | TV_MISSILE_MODE_BW | TV_MISSILE_MODE_THERMAL;

    @SerializedName("tv_missile_control_range")
    private float tvMissileControlRange = 2000f;
    @SerializedName("tv_missile_timeout_tick")
    private int tvMissileTimeoutTick = 200;
    @SerializedName("tv_missile_video_modes")
    private List<String> tvVideoModes;

    public float getTVMissileControlRange() {
        return tvMissileControlRange;
    }

    public int getTVMissileTimeoutTick() {
        return tvMissileTimeoutTick;
    }

    public int getTVMissileVideoModeMask() {
        if (tvVideoModes == null || tvVideoModes.isEmpty()) {
            return TV_MISSILE_MODE_ALL;
        }
        int mask = 0;
        for (String mode : tvVideoModes) {
            mask |= parseMode(mode);
        }
        return mask != 0 ? mask : TV_MISSILE_MODE_ALL;
    }

    public int getDefaultTVMissileVideoMode() {
        if (tvVideoModes != null) {
            for (String mode : tvVideoModes) {
                int parsed = parseMode(mode);
                if (parsed != 0) {
                    return parsed;
                }
            }
        }
        return TV_MISSILE_MODE_COLOR;
    }

    private static int parseMode(String mode) {
        if (mode == null) {
            return 0;
        }
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "COLOR" -> TV_MISSILE_MODE_COLOR;
            case "BW", "BLACK_WHITE", "BLACKWHITE", "MONO", "MONOCHROME" -> TV_MISSILE_MODE_BW;
            case "THERMAL", "IR" -> TV_MISSILE_MODE_THERMAL;
            default -> 0;
        };
    }
}
