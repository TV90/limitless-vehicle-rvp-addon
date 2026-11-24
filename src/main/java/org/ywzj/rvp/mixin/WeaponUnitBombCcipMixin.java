package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.util.RVP_CcipUtil;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
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
    @Shadow(remap = false) public abstract boolean isParentWeaponUnitAim();
    @Shadow(remap = false) public abstract WeaponUnit getRootParentWeaponUnit();

    @Inject(method = "aimHitPosition()Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ywzj_rvp$aimHitWithoutBolts(CallbackInfoReturnable<Vec3> cir) {
        ywzj_rvp$traceSightWithoutBolts(1.0f, cir);
    }

    @Inject(method = "aimAtPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ywzj_rvp$aimAtWithoutBolts(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        ywzj_rvp$traceSightWithoutBolts(partialTick, cir);
    }

    @Unique
    private void ywzj_rvp$traceSightWithoutBolts(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        WeaponUnit unit = (WeaponUnit) (Object) this;
        // 本体会对车上所有武器站更新落点，含仅用于观瞄、没有炮闩的站位。
        if (unit.getBolts().isEmpty()) {
            // 没有俯仰组时用本体的武器站枢轴，避开部分观瞄类型对空旋转组的访问。
            Vec3 start = unit.xTurnGroup == null
                    ? unit.worldPosition(unit.getPivotOffset(), partialTick)
                    : unit.worldOpticalSightPosition(partialTick);
            cir.setReturnValue(RVP_AimContexts.opticalSightHit(unit, start, partialTick));
        }
    }

    @Inject(method = "currentWeaponHitPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$computeRvpBombCcip(CallbackInfoReturnable<Vec3> cir) {
        // 用 RVP_WeaponSensorHelper 判断动态传感器（eo_ccip 模式：非瞄准视角为 CCIP、
        // 瞄准视角为 EO），替代本体静态 getFireControlSensorType()，恢复传感器覆盖 mixin 被删前的行为。
        if (RVP_WeaponSensorHelper.effectiveSensorType((WeaponUnit) (Object) this)
                != WeaponUnitData.FireControlSensorType.CCIP) {
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
        // 当前发射站缺少炮闩时，交回本体的观瞄落点查询。
        if (currentWeaponUnit.getBolts().isEmpty()) {
            return;
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
