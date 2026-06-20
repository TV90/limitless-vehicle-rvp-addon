package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.ext.WeaponUnitPojoExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitPojo;

@Mixin(value = WeaponUnitData.class, remap = false)
public class WeaponUnitDataMixin implements WeaponUnitDataExt {
    @Unique
    private String ywzj_rvp$fireControlMode = "";

    @Unique
    private float ywzj_rvp$rfOffAxisDeg = 10.0f;

    @Inject(method = "<init>(Lorg/ywzj/vehicle/custom/part/data/WeaponUnitPojo;)V", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$init(WeaponUnitPojo pojo, CallbackInfo ci) {
        if (pojo instanceof WeaponUnitPojoExt ext) {
            this.ywzj_rvp$fireControlMode = ext.ywzj_rvp$getFireControlMode();
            this.ywzj_rvp$rfOffAxisDeg = ext.ywzj_rvp$getRfOffAxisDeg();
        }
    }

    @Override
    public String ywzj_rvp$getFireControlMode() {
        return ywzj_rvp$fireControlMode;
    }

    @Override
    public float ywzj_rvp$getRfOffAxisDeg() {
        return ywzj_rvp$rfOffAxisDeg;
    }
}
