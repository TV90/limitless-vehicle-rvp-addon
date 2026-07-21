package org.ywzj.rvp.mixin;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.WeaponUnitWeaponBayOverrideExt;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientVehicleAction;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitToggleCurrentWeaponBayMixin {

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "toggleCurrentWeaponBay", at = @At("HEAD"), cancellable = true, remap = false)
    private void rvp$toggleActiveBay(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        WeaponBayUnit weaponBayUnit = rvp$resolveActiveWeaponBay(self);
        if (weaponBayUnit == null) {
            return;
        }
        if (self instanceof WeaponUnitWeaponBayOverrideExt ext) {
            ext.ywzj_rvp$markWeaponBayManualOverride(
                self.getCurrentWeaponIndex(),
                self.getCurrentSecondaryWeaponIndex()
            );
        }
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = self.getVehicle().getId();
        action.partUnitIndex = weaponBayUnit.getIndex();
        action.togglePartUnitState = true;
        Channel.CHANNEL.sendToServer(action);
        ci.cancel();
    }

    @Unique
    private WeaponBayUnit rvp$resolveActiveWeaponBay(WeaponUnit self) {
        WeaponBayUnit targetBay = null;
        int curPrimaryIdx = self.getCurrentWeaponIndex();
        if (curPrimaryIdx >= 0 && curPrimaryIdx < self.weapons.size()) {
            targetBay = self.weaponBayUnits.get(self.weapons.get(curPrimaryIdx));
        }
        int curSecondaryIdx = self.getCurrentSecondaryWeaponIndex();
        if (targetBay == null && curSecondaryIdx >= 0 && curSecondaryIdx < self.secondaryWeapons.size()) {
            targetBay = self.weaponBayUnits.get(self.secondaryWeapons.get(curSecondaryIdx));
        }
        return targetBay;
    }
}
