package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 在 {@code AbstractVehicle.initData(BaseVehicleData)} 完成时，
 * 通过反射读取 {@code BaseVehicleData} 上的 {@code ywzj_rvp$uiPreset} 字段
 * （由 {@link BaseVehicleDataUIPresetMixin} 在 build 时写入），
 * 并写入 {@link VehicleUIPresetCache}。
 */
@Mixin(value = AbstractVehicle.class, remap = false)
public class AbstractVehicleUIPresetMixin {

    @Inject(method = "initData(Lorg/ywzj/vehicle/custom/vehicle/BaseVehicleData;)V",
            at = @At("TAIL"), remap = false)
    private void ywzj_rvp$cacheUiPreset(BaseVehicleData vehicleData, CallbackInfo ci) {
        String preset = BaseVehicleDataUIPresetMixin.readFrom(vehicleData);
        VehicleUIPresetCache.put((AbstractVehicle) (Object) this, preset);
    }
}
