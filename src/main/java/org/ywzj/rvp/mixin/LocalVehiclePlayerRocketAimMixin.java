package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.gui.RVP_RocketCcipOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_RocketCcipState;
import org.ywzj.rvp.weapon.RVP_RocketBallistics;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
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
        WeaponUnit weaponUnit = self.getWeaponUnit();
        this.ywzj_rvp$prevWeaponHitPos = weaponUnit != null ? weaponUnit.weaponHitPos : null;
    }

    @Inject(method = "tickAim", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$applyRocketBallisticAim(CallbackInfo ci) {
        LocalVehiclePlayer self = (LocalVehiclePlayer) (Object) this;
        WeaponUnit weaponUnit = self.getWeaponUnit();
        if (RVP_ClientHitlState.isActive() && this.ywzj_rvp$prevWeaponHitPos != null) {
            if (weaponUnit != null) {
                weaponUnit.weaponHitPosO = this.ywzj_rvp$prevWeaponHitPos;
                weaponUnit.weaponHitPos = this.ywzj_rvp$prevWeaponHitPos;
            }
            return;
        }
        if (weaponUnit == null || weaponUnit.getCurrentWeapon().isEmpty()) {
            if (self.onVehicle()) {
                RVP_RocketCcipState.clear(self.getVehicle().getId());
            }
            return;
        }
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().get();
        if (!RVP_RocketCcipOverlay.isBallisticRocketWeapon(currentWeapon, weaponUnit)) {
            if (self.onVehicle()) {
                RVP_RocketCcipState.clear(self.getVehicle().getId());
            }
            return;
        }
        WeaponUnit rocketWeaponUnit = currentWeapon.getWeaponUnit();
        if (rocketWeaponUnit == null) {
            if (self.onVehicle()) {
                RVP_RocketCcipState.clear(self.getVehicle().getId());
            }
            return;
        }
        if (!self.onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = self.getVehicle();
        Vec3 rawHit = null;
        ResourceLocation weaponId = null;
        if (currentWeapon instanceof VehicleRocket rocket) {
            rawHit = RVP_RocketBallistics.computeWeaponImpact(
                    vehicle.level(), rocketWeaponUnit, vehicle.getDeltaMovement(), rocket.getData(), vehicle);
            weaponId = rocket.getData().getWeaponId();
        } else if (currentWeapon instanceof RVP_ProjectileWeapon weapon
                && weapon.getData().getWeaponKind() == RVP_EnumWeaponKind.ROCKET) {
            rawHit = RVP_RocketBallistics.computeWeaponImpact(
                    vehicle.level(), rocketWeaponUnit, vehicle.getDeltaMovement(), weapon.getData(), vehicle);
            weaponId = weapon.getData().getWeaponId();
        }
        if (weaponId == null) {
            return;
        }
        Vec3 hit = RVP_RocketCcipState.smooth(vehicle.getId(), weaponId, vehicle.tickCount, rawHit);
        if (hit == null) {
            return;
        }
        weaponUnit.weaponHitPosO = this.ywzj_rvp$prevWeaponHitPos == null ? hit : this.ywzj_rvp$prevWeaponHitPos;
        weaponUnit.weaponHitPos = hit;
        rocketWeaponUnit.weaponHitPosO = this.ywzj_rvp$prevWeaponHitPos == null ? hit : this.ywzj_rvp$prevWeaponHitPos;
        rocketWeaponUnit.weaponHitPos = hit;
    }
}
