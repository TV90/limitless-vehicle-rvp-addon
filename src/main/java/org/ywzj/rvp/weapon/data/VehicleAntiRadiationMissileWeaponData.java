package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;

public class VehicleAntiRadiationMissileWeaponData extends VehicleMissileWeaponData {

    @SerializedName("anti_radiation_scan_interval_tick")
    private int antiRadiationScanIntervalTick = 2;
    @SerializedName("anti_radiation_memory_tick")
    private int antiRadiationMemoryTick = 0;
    @SerializedName("anti_radiation_seek_range")
    private float antiRadiationSeekRange = 1024f;
    @SerializedName("anti_radiation_allow_reacquire")
    private boolean antiRadiationAllowReacquire = true;
    @SerializedName("anti_radiation_allow_fire_without_seeker")
    private boolean antiRadiationAllowFireWithoutSeeker = true;
    @SerializedName("anti_radiation_radiation_pulse_memory_tick")
    private int antiRadiationRadiationPulseMemoryTick = 25;
    @SerializedName("anti_radiation_locked_bonus")
    private float antiRadiationLockedBonus = 0.5f;
    @SerializedName("anti_radiation_preselect_enabled")
    private boolean antiRadiationPreselectEnabled = true;

    public int getAntiRadiationScanIntervalTick() {
        return antiRadiationScanIntervalTick;
    }

    public int getAntiRadiationMemoryTick() {
        return antiRadiationMemoryTick;
    }

    public float getAntiRadiationSeekRange() {
        return antiRadiationSeekRange;
    }

    public boolean isAntiRadiationAllowReacquire() {
        return antiRadiationAllowReacquire;
    }

    public boolean isAntiRadiationAllowFireWithoutSeeker() {
        return antiRadiationAllowFireWithoutSeeker;
    }

    public int getAntiRadiationRadiationPulseMemoryTick() {
        return antiRadiationRadiationPulseMemoryTick;
    }

    public float getAntiRadiationLockedBonus() {
        return antiRadiationLockedBonus;
    }

    public boolean isAntiRadiationPreselectEnabled() {
        return antiRadiationPreselectEnabled;
    }
}
