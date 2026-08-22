package org.ywzj.rvp.mixin;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.RadarUnitPojoExt;
import org.ywzj.rvp.radar.RVP_RadarHmsMode;
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
    public JsonElement ywzj_rvp$enableHms = null;

    @SerializedName("scan_min_height")
    @Unique
    public float ywzj_rvp$scanMinHeight = 25f;

    @SerializedName("scan_max_height")
    @Unique
    public float ywzj_rvp$scanMaxHeight = 10000f;

    /** 雷达对箔条目标的锁定抗性 0~1：箔条可作为雷达锁定目标，但按此值施加优先级罚分（越大越难被选中，非完全不可锁）。 */
    @SerializedName("chaff_resistance")
    @Unique
    public float ywzj_rvp$chaffResistance = 0.5f;

    /** 仅扫描/跟踪载具：true 时扫描与锁定表只保留 AbstractVehicle 目标，排除弹药、干扰物等非载具实体。默认 false。 */
    @SerializedName("scan_vehicle_only")
    @Unique
    public boolean ywzj_rvp$scanVehicleOnly = false;

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
    public RVP_RadarHmsMode ywzj_rvp$getHmsMode() {
        return RVP_RadarHmsMode.fromJson(ywzj_rvp$enableHms);
    }

    @Override
    public float ywzj_rvp$getScanMinHeight() {
        return ywzj_rvp$scanMinHeight;
    }

    @Override
    public float ywzj_rvp$getScanMaxHeight() {
        return ywzj_rvp$scanMaxHeight;
    }

    @Override
    public float ywzj_rvp$getChaffResistance() {
        return Float.isFinite(ywzj_rvp$chaffResistance) ? Math.max(0f, ywzj_rvp$chaffResistance) : 0f;
    }

    @Override
    public boolean ywzj_rvp$isScanVehicleOnly() {
        return ywzj_rvp$scanVehicleOnly;
    }
}
