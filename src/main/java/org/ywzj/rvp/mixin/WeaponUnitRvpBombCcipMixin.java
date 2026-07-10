package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.client.state.RVP_BombCcipUtil;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitRvpBombCcipMixin {

    @Inject(method = "currentWeaponHitPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$useRvpBombCcip(CallbackInfoReturnable<Vec3> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (self.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.CCIP) {
            return;
        }
        AbstractVehicleWeapon<?> currentWeapon = RVP_LaserWeapons.unwrap(self.getCurrentWeapon().orElse(null));
        if (!(currentWeapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data.getWeaponKind() != RVP_EnumWeaponKind.BOMB) {
            return;
        }
        cir.setReturnValue(RVP_BombCcipUtil.computeImpact(self.getVehicle(), self, data));
    }
}
