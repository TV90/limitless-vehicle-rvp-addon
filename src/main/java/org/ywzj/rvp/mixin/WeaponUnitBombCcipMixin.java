package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.util.RVP_CcipUtil;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitBombCcipMixin {

    @Shadow(remap = false) public abstract Optional<AbstractVehicleWeapon<?>> getCurrentWeapon();
    @Shadow(remap = false) public abstract WeaponUnitData.FireControlSensorType getFireControlSensorType();
    @Shadow(remap = false) public abstract boolean isParentWeaponUnitAim();
    @Shadow(remap = false) public abstract WeaponUnit getRootParentWeaponUnit();
    @Shadow(remap = false) public abstract Vec3 worldPivotPosition();

    @Inject(method = "currentWeaponHitPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$computeRvpBombCcip(CallbackInfoReturnable<Vec3> cir) {
        if (getFireControlSensorType() != WeaponUnitData.FireControlSensorType.CCIP) {
            return;
        }
        Optional<AbstractVehicleWeapon<?>> vehicleWeaponOptional = getCurrentWeapon();
        if (vehicleWeaponOptional.isEmpty()) {
            return;
        }
        AbstractVehicleWeapon<?> vehicleWeapon = vehicleWeaponOptional.get();
        if (!(vehicleWeapon instanceof RVP_WeaponBase weapon)) {
            return;
        }
        if (weapon.getData().getWeaponKind() != RVP_EnumWeaponKind.BOMB) {
            return;
        }
        WeaponUnit currentWeaponUnit = vehicleWeapon.getWeaponUnit();
        if (currentWeaponUnit != null && currentWeaponUnit.isParentWeaponUnitAim()) {
            currentWeaponUnit = currentWeaponUnit.getRootParentWeaponUnit();
        }
        if (currentWeaponUnit == null) {
            currentWeaponUnit = (WeaponUnit) (Object) this;
        }
        WeaponUnit self = (WeaponUnit) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();
        Vec3 releasePos = RVP_AimContexts.muzzle(currentWeaponUnit.aimContext());
        Vec3 aimDir = VectorUtil.rotToVec(currentWeaponUnit.aimContext().direction.x, currentWeaponUnit.aimContext().direction.y).normalize();
        Vec3 startVelocity = aimDir.scale(weapon.getData().resolveMuzzleSpeed(RVP_EnumWeaponKind.BOMB));
        if (weapon.getData().isInheritVehicleVelocity()) {
            startVelocity = startVelocity.add(vehicle.getDeltaMovement());
        }
        cir.setReturnValue(RVP_CcipUtil.computeBombImpact(
                vehicle.level(),
                releasePos,
                startVelocity,
                weapon.getData()
        ));
    }
}
