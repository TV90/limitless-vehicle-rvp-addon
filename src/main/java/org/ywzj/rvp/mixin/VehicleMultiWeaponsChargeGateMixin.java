package org.ywzj.rvp.mixin;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

@OnlyIn(Dist.CLIENT)
@Mixin(value = VehicleMultiWeapons.class, remap = false)
public abstract class VehicleMultiWeaponsChargeGateMixin {

    @Shadow(remap = false)
    public abstract AbstractVehicleWeapon<?> getSelectedWeapon();

    @Inject(method = "doClientShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$chargeGate(CallbackInfoReturnable<Boolean> cir) {
        AbstractVehicleWeapon<?> selected = getSelectedWeapon();
        if (!(selected instanceof RVP_WeaponBase rvp)) {
            return;
        }
        rvp.getFireController().syncClientInput();
        if (!rvp.getFireController().shouldAttemptClientShot()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "doClientShoot", at = @At("RETURN"), remap = false)
    private void ywzj_rvp$afterShot(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        AbstractVehicleWeapon<?> selected = getSelectedWeapon();
        if (selected instanceof RVP_WeaponBase rvp) {
            rvp.getFireController().onShotFired();
        }
    }
}
