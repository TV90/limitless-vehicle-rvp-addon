package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.RadarUnitPojoExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitPojo;

@Mixin(value = RadarUnitPojo.class, remap = false)
public class RadarUnitPojoMixin implements RadarUnitPojoExt {
    @SerializedName("radar_role")
    @Unique
    public String ywzj_rvp$radarRole = "all";

    @SerializedName("scan_animation_mode")
    @Unique
    public String ywzj_rvp$scanAnimationMode = "mechanical";

    @SerializedName("nctr_mode")
    @Unique
    public String ywzj_rvp$nctrMode = "NONE";

    @SerializedName("scan_period_tick")
    @Unique
    public int ywzj_rvp$scanPeriodTick = 0;

    @SerializedName("scan_line_when_locked")
    @Unique
    public boolean ywzj_rvp$scanLineWhenLocked = false;

    @SerializedName("contact_hold_tick")
    @Unique
    public int ywzj_rvp$contactHoldTick = 0;

    @SerializedName("enable_hms")
    @Unique
    public boolean ywzj_rvp$enableHms = true;

    @SerializedName("scan_min_height")
    @Unique
    public float ywzj_rvp$scanMinHeight = 25f;

    @SerializedName("scan_max_height")
    @Unique
    public float ywzj_rvp$scanMaxHeight = 10000f;

    @Override
    public String ywzj_rvp$getRadarRole() {
        return ywzj_rvp$radarRole;
    }

    @Override
    public String ywzj_rvp$getScanAnimationMode() {
        return ywzj_rvp$scanAnimationMode;
    }

    @Override
    public String ywzj_rvp$getNctrMode() {
        return ywzj_rvp$nctrMode;
    }

    @Override
    public int ywzj_rvp$getScanPeriodTick() {
        return ywzj_rvp$scanPeriodTick;
    }

    @Override
    public boolean ywzj_rvp$isScanLineWhenLocked() {
        return ywzj_rvp$scanLineWhenLocked;
    }

    @Override
    public int ywzj_rvp$getContactHoldTick() {
        return ywzj_rvp$contactHoldTick;
    }

    @Override
    public boolean ywzj_rvp$isEnableHms() {
        return ywzj_rvp$enableHms;
    }

    @Override
    public float ywzj_rvp$getScanMinHeight() {
        return ywzj_rvp$scanMinHeight;
    }

    @Override
    public float ywzj_rvp$getScanMaxHeight() {
        return ywzj_rvp$scanMaxHeight;
    }
}
