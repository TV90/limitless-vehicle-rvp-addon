package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.RadarUnitPojoExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitPojo;

@Mixin(value = RadarUnitPojo.class, remap = false)
public class RadarUnitPojoMixin implements RadarUnitPojoExt {
    @SerializedName("scan_animation_mode")
    @Unique
    public String ywzj_rvp$scanAnimationMode = "mechanical";

    @SerializedName("scan_period_tick")
    @Unique
    public int ywzj_rvp$scanPeriodTick = 0;

    @SerializedName("scan_line_when_locked")
    @Unique
    public boolean ywzj_rvp$scanLineWhenLocked = false;

    @SerializedName("contact_hold_tick")
    @Unique
    public int ywzj_rvp$contactHoldTick = 0;

    @SerializedName("track_ground_targets")
    @Unique
    public boolean ywzj_rvp$trackGroundTargets = false;

    @Override
    public String ywzj_rvp$getScanAnimationMode() {
        return ywzj_rvp$scanAnimationMode;
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
    public boolean ywzj_rvp$isTrackGroundTargets() {
        return ywzj_rvp$trackGroundTargets;
    }
}
