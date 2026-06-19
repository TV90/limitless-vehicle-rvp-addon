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
    private String ywzj_rvp$scanAnimationMode = "mechanical";

    @Unique
    private int ywzj_rvp$scanPeriodTick = 0;

    @Unique
    private boolean ywzj_rvp$scanLineWhenLocked = false;

    @Unique
    private int ywzj_rvp$contactHoldTick = 0;

    @Unique
    private boolean ywzj_rvp$enableHms = true;

    @Inject(method = "<init>(Lorg/ywzj/vehicle/custom/part/data/RadarUnitPojo;)V", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$init(RadarUnitPojo pojo, CallbackInfo ci) {
        if (pojo instanceof RadarUnitPojoExt ext) {
            this.ywzj_rvp$scanAnimationMode = ext.ywzj_rvp$getScanAnimationMode();
            this.ywzj_rvp$scanPeriodTick = ext.ywzj_rvp$getScanPeriodTick();
            this.ywzj_rvp$scanLineWhenLocked = ext.ywzj_rvp$isScanLineWhenLocked();
            this.ywzj_rvp$contactHoldTick = ext.ywzj_rvp$getContactHoldTick();
            this.ywzj_rvp$enableHms = ext.ywzj_rvp$isEnableHms();
        }
    }

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
    public boolean ywzj_rvp$isEnableHms() {
        return ywzj_rvp$enableHms;
    }
}
