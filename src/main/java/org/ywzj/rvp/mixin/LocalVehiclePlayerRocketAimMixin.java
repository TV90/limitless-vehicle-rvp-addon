package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RvpRocketCcipState;
import org.ywzj.rvp.weapon.RvpRocketBallistics;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;

@Mixin(value = LocalVehiclePlayer.class, remap = false)
public class LocalVehiclePlayerRocketAimMixin {

    @Unique
    private Vec3 ywzj_rvp$prevWeaponHitPos;

    @Inject(method = "tickAim", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$capturePreviousRocketAim(CallbackInfo ci) {
        LocalVehiclePlayer self = (LocalVehiclePlayer) (Object) this;
        this.ywzj_rvp$prevWeaponHitPos = self.weaponHitPos;
    }

    @Inject(method = "tickAim", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$applyRocketBallisticAim(CallbackInfo ci) {
        LocalVehiclePlayer self = (LocalVehiclePlayer) (Object) this;
        WeaponUnit weaponUnit = self.getWeaponUnit();
        if (weaponUnit == null || weaponUnit.getCurrentWeapon().isEmpty()) {
            if (self.onVehicle()) {
                RvpRocketCcipState.clear(self.getVehicle().getId());
            }
            return;
        }
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().get();
        if (!(currentWeapon instanceof VehicleRocket rocket)) {
            if (self.onVehicle()) {
                RvpRocketCcipState.clear(self.getVehicle().getId());
            }
            return;
        }
        WeaponUnit rocketWeaponUnit = rocket.getWeaponUnit();
        if (rocketWeaponUnit == null) {
            if (self.onVehicle()) {
                RvpRocketCcipState.clear(self.getVehicle().getId());
            }
            return;
        }
        if (!self.onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = self.getVehicle();
        Vec3 rawHit = RvpRocketBallistics.computeWeaponImpact(vehicle.level(), rocketWeaponUnit, vehicle.getDeltaMovement(), rocket.getData(), vehicle);
        Vec3 hit = RvpRocketCcipState.smooth(vehicle.getId(), rocket.getData().getWeaponId(), vehicle.tickCount, rawHit);
        if (hit == null) {
            return;
        }
        self.weaponHitPosO = this.ywzj_rvp$prevWeaponHitPos == null ? hit : this.ywzj_rvp$prevWeaponHitPos;
        self.weaponHitPos = hit;
    }
}
