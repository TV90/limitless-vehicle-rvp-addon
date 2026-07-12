package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSensorOverrideMixin {

    @Inject(method = "getFireControlSensorType", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$overrideSensorTypeByCurrentWeapon(CallbackInfoReturnable<WeaponUnitData.FireControlSensorType> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isEmpty()) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = weaponOpt.get();
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if ("eo_ccip".equalsIgnoreCase(data.getFireControlSensorMode())) {
            if (LocalVehiclePlayer.instance != null
                    && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
                cir.setReturnValue(WeaponUnitData.FireControlSensorType.EO);
            } else {
                cir.setReturnValue(WeaponUnitData.FireControlSensorType.CCIP);
            }
            return;
        }
        WeaponUnitData.FireControlSensorType override = data.getFireControlSensorTypeOverride();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
