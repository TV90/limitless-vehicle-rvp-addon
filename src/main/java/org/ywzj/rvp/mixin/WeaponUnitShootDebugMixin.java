package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.debug.RVP_WeaponOriginDebug;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.List;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitShootDebugMixin {

    @Inject(
            method = "shoot",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/weapon/AbstractVehicleWeapon;shoot(Ljava/util/List;Lnet/minecraft/world/entity/LivingEntity;)Z"
            ),
            remap = false
    )
    private void ywzj_rvp$logShootInvocation(int weaponIndex, List<AimContext> aimContexts,
                                             LivingEntity operator, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        AbstractVehicleWeapon<?> resolvedWeapon = weaponIndex >= 0 && weaponIndex < self.indexedWeapons.size()
                ? self.indexedWeapons.get(weaponIndex)
                : null;
        RVP_WeaponOriginDebug.noteShootInvocation(self, weaponIndex, resolvedWeapon, aimContexts, operator);
    }
}
