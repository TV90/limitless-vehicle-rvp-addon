package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.vehicle.RVP_GroupedWeaponSlotAssembler;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitGroupedSlotMixin {

    @Inject(method = "combineAndInit", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$assembleGroupedSlots(Map<String, PartUnit<?>> partUnitsView,
                                               AbstractVehicle vehicle,
                                               CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!RVP_GroupedWeaponSlotAssembler.shouldHandle(self)) {
            return;
        }
        RVP_GroupedWeaponSlotAssembler.assemble(self, partUnitsView, vehicle);
        ci.cancel();
    }
}
