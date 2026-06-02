package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;

public class VehicleGPSBombWeaponData extends VehicleMissileWeaponData {

    @SerializedName("fuse_delay_tick")
    private int fuseDelayTick = 0;
    @SerializedName("gravity_scale")
    private float gravityScale = 1.0f;
    @SerializedName("terminal_ir_enabled")
    private boolean terminalIrEnabled = false;
    @SerializedName("terminal_ir_activation_distance")
    private float terminalIrActivationDistance = 0f;
    @SerializedName("terminal_ir_seeker_fov")
    private float terminalIrSeekerFov = 30f;
    @SerializedName("terminal_ir_seek_range")
    private float terminalIrSeekRange = 64f;
    @SerializedName("terminal_ir_scan_interval_tick")
    private int terminalIrScanIntervalTick = 2;
    @SerializedName("terminal_ir_vehicle_only")
    private boolean terminalIrVehicleOnly = true;
    @SerializedName("terminal_ir_smoke_break_lock")
    private boolean terminalIrSmokeBreakLock = true;
    @SerializedName("terminal_ir_memory_tick")
    private int terminalIrMemoryTick = 0;
    @SerializedName("terminal_ir_allow_reacquire")
    private boolean terminalIrAllowReacquire = true;

    public int getFuseDelayTick() {
        return fuseDelayTick;
    }

    public float getGravityScale() {
        return gravityScale;
    }

    public boolean isTerminalIrEnabled() {
        return terminalIrEnabled;
    }

    public float getTerminalIrActivationDistance() {
        return terminalIrActivationDistance;
    }

    public float getTerminalIrSeekerFov() {
        return terminalIrSeekerFov;
    }

    public float getTerminalIrSeekRange() {
        return terminalIrSeekRange;
    }

    public int getTerminalIrScanIntervalTick() {
        return terminalIrScanIntervalTick;
    }

    public boolean isTerminalIrVehicleOnly() {
        return terminalIrVehicleOnly;
    }

    public boolean isTerminalIrSmokeBreakLock() {
        return terminalIrSmokeBreakLock;
    }

    public int getTerminalIrMemoryTick() {
        return terminalIrMemoryTick;
    }

    public boolean isTerminalIrAllowReacquire() {
        return terminalIrAllowReacquire;
    }
}
