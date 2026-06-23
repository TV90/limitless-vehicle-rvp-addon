package org.ywzj.rvp.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = VehicleScopeOverlay.class, remap = false)
public class VehicleScopeOverlayWeaponUnitsMixin {

    @Redirect(
            method = "renderVehicleHeading",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;isSeat()Z"),
            remap = false
    )
    private boolean ywzj_rvp$showWeaponUnitsInBones(WeaponUnit weaponUnit) {
        ResourceLocation vehicleId = weaponUnit.getVehicle().getVehicleId();
        if (vehicleId != null && "rvp".equals(vehicleId.getNamespace())) {
            return true;
        }
        return weaponUnit.isSeat();
    }
}
