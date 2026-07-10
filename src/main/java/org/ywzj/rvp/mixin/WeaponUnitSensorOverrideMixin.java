package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
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
        AbstractVehicleWeapon<?> weapon = RVP_LaserWeapons.unwrap(weaponOpt.get());
        if (weapon == null) {
            return;
        }
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data.isEoCcipSensorMode()) {
            cir.setReturnValue(resolveEoCcipSensorType());
            return;
        }
        WeaponUnitData.FireControlSensorType override = data.getFireControlSensorTypeOverride();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }

    private static WeaponUnitData.FireControlSensorType resolveEoCcipSensorType() {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            return WeaponUnitData.FireControlSensorType.EO;
        }
        return LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE
                ? WeaponUnitData.FireControlSensorType.EO
                : WeaponUnitData.FireControlSensorType.CCIP;
    }
}
