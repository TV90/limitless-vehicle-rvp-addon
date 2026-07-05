package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.ext.RadarUnitPojoExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.custom.part.data.RadarUnitPojo;

@Mixin(value = RadarUnitData.class, remap = false)
public class RadarUnitDataMixin implements RadarUnitDataExt {
    @Unique
    private String ywzj_rvp$radarRole = "ALL";

    @Unique
    private String ywzj_rvp$scanAnimationMode = "mechanical";

    @Unique
    private String ywzj_rvp$nctrMode = "NONE";

    @Unique
    private int ywzj_rvp$scanPeriodTick = 0;

    @Unique
    private boolean ywzj_rvp$scanLineWhenLocked = false;

    @Unique
    private int ywzj_rvp$contactHoldTick = 0;

    @Unique
    private boolean ywzj_rvp$enableHms = true;

    @Unique
    private float ywzj_rvp$scanMinHeight = 25f;

    @Unique
    private float ywzj_rvp$scanMaxHeight = 10000f;

    @Inject(method = "<init>(Lorg/ywzj/vehicle/custom/part/data/RadarUnitPojo;)V", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$init(RadarUnitPojo pojo, CallbackInfo ci) {
        if (pojo instanceof RadarUnitPojoExt ext) {
            this.ywzj_rvp$radarRole = ext.ywzj_rvp$getRadarRole();
            this.ywzj_rvp$scanAnimationMode = ext.ywzj_rvp$getScanAnimationMode();
            this.ywzj_rvp$nctrMode = ext.ywzj_rvp$getNctrMode();
            this.ywzj_rvp$scanPeriodTick = ext.ywzj_rvp$getScanPeriodTick();
            this.ywzj_rvp$scanLineWhenLocked = ext.ywzj_rvp$isScanLineWhenLocked();
            this.ywzj_rvp$contactHoldTick = ext.ywzj_rvp$getContactHoldTick();
            this.ywzj_rvp$enableHms = ext.ywzj_rvp$isEnableHms();
            this.ywzj_rvp$scanMinHeight = ext.ywzj_rvp$getScanMinHeight();
            this.ywzj_rvp$scanMaxHeight = ext.ywzj_rvp$getScanMaxHeight();
        }
    }

    @Override
    public String ywzj_rvp$getRadarRole() {
        if (ywzj_rvp$radarRole == null) {
            return "ALL";
        }
        String role = ywzj_rvp$radarRole.trim();
        if ("SEARCH".equalsIgnoreCase(role) || "SEARCH_ONLY".equalsIgnoreCase(role)) {
            return "SEARCH";
        }
        if ("FIRE_CONTROL".equalsIgnoreCase(role) || "FCR".equalsIgnoreCase(role)) {
            return "FIRE_CONTROL";
        }
        return "ALL";
    }

    @Override
    public String ywzj_rvp$getScanAnimationMode() {
        return ywzj_rvp$scanAnimationMode;
    }

    @Override
    public String ywzj_rvp$getNctrMode() {
        if (ywzj_rvp$nctrMode == null) {
            return "NONE";
        }
        String mode = ywzj_rvp$nctrMode.trim();
        if ("EARLY".equalsIgnoreCase(mode)) {
            return "EARLY";
        }
        if ("MODERN".equalsIgnoreCase(mode)) {
            return "MODERN";
        }
        return "NONE";
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
