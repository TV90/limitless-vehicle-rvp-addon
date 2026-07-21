package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.gui.RVP_RocketCcipOverlay;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ArtilleryFireControlState;
import org.ywzj.rvp.client.state.RVP_RocketCcipState;
import org.ywzj.rvp.client.state.RVP_RocketCcipScreenState;
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
    private Vec3 ywzj_rvp$weaponHitPosBeforeAim;

    @Unique
    private Vec3 ywzj_rvp$previousRocketCcipHit;

    @Unique
    private ResourceLocation ywzj_rvp$previousRocketWeaponId;

    @Unique
    private int ywzj_rvp$previousRocketVehicleId = Integer.MIN_VALUE;

    @Inject(method = "tickAim", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$capturePreviousRocketAim(CallbackInfo ci) {
        LocalVehiclePlayer self = (LocalVehiclePlayer) (Object) this;
        WeaponUnit weaponUnit = self.getWeaponUnit();
        this.ywzj_rvp$weaponHitPosBeforeAim = weaponUnit != null ? weaponUnit.weaponHitPos : null;
    }

    @Inject(method = "tickAim", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$applyRocketBallisticAim(CallbackInfo ci) {
        LocalVehiclePlayer self = (LocalVehiclePlayer) (Object) this;
        WeaponUnit weaponUnit = self.getWeaponUnit();
        if (RVP_ClientHitlState.isActive() && this.ywzj_rvp$weaponHitPosBeforeAim != null) {
            if (weaponUnit != null) {
                weaponUnit.weaponHitPosO = this.ywzj_rvp$weaponHitPosBeforeAim;
                weaponUnit.weaponHitPos = this.ywzj_rvp$weaponHitPosBeforeAim;
            }
            return;
        }
        if (weaponUnit == null || weaponUnit.getCurrentWeapon().isEmpty()) {
            if (self.onVehicle()) {
                ywzj_rvp$clearRocketCcip(self.getVehicle().getId());
            }
            return;
        }
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().get();
        if (!RVP_RocketCcipOverlay.isBallisticRocketWeapon(currentWeapon, weaponUnit)) {
            if (self.onVehicle()) {
                ywzj_rvp$clearRocketCcip(self.getVehicle().getId());
            }
            return;
        }
        WeaponUnit rocketWeaponUnit = currentWeapon.getWeaponUnit();
        if (rocketWeaponUnit == null) {
            if (self.onVehicle()) {
                ywzj_rvp$clearRocketCcip(self.getVehicle().getId());
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
            Vec3 artilleryTarget = RVP_ArtilleryFireControlState.getDesignatedTarget();
            if (weapon.getData().getMiscData().isArtilleryMap() && artilleryTarget != null) {
                rawHit = RVP_RocketBallistics.computeArtilleryWeaponImpact(
                        vehicle.level(), rocketWeaponUnit, vehicle.getDeltaMovement(), weapon.getData(), vehicle,
                        artilleryTarget.y, RVP_TacticalMapCache::getCachedHeight);
            } else {
                rawHit = RVP_RocketBallistics.computeWeaponImpact(
                        vehicle.level(), rocketWeaponUnit, vehicle.getDeltaMovement(), weapon.getData(), vehicle,
                        RVP_RocketBallistics.DEFAULT_PREDICTION_TICK);
            }
            weaponId = weapon.getData().getWeaponId();
        }
        if (weaponId == null) {
            return;
        }
        Vec3 hit = RVP_RocketCcipState.smooth(vehicle.getId(), weaponId, vehicle.tickCount, rawHit);
        if (hit == null) {
            return;
        }
        boolean sameCcipChain = vehicle.getId() == this.ywzj_rvp$previousRocketVehicleId
                && weaponId.equals(this.ywzj_rvp$previousRocketWeaponId)
                && this.ywzj_rvp$previousRocketCcipHit != null;
        Vec3 previousHit = sameCcipChain ? this.ywzj_rvp$previousRocketCcipHit : hit;
        weaponUnit.weaponHitPosO = previousHit;
        weaponUnit.weaponHitPos = hit;
        rocketWeaponUnit.weaponHitPosO = previousHit;
        rocketWeaponUnit.weaponHitPos = hit;
        this.ywzj_rvp$previousRocketCcipHit = hit;
        this.ywzj_rvp$previousRocketWeaponId = weaponId;
        this.ywzj_rvp$previousRocketVehicleId = vehicle.getId();
    }

    @Inject(method = "tickAim", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$applyArtilleryMapAim(CallbackInfo ci) {
        RVP_ArtilleryFireControlState.applyAutomaticAim((LocalVehiclePlayer) (Object) this);
    }

    @Unique
    private void ywzj_rvp$clearRocketCcip(int vehicleId) {
        RVP_RocketCcipState.clear(vehicleId);
        RVP_RocketCcipScreenState.clear(vehicleId);
        this.ywzj_rvp$previousRocketCcipHit = null;
        this.ywzj_rvp$previousRocketWeaponId = null;
        this.ywzj_rvp$previousRocketVehicleId = Integer.MIN_VALUE;
    }
}
