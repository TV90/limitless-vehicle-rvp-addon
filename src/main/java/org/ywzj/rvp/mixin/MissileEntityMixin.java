package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.entity.weapon.TVMissileEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;

@Mixin(value = MissileEntity.class, remap = false)
public class MissileEntityMixin {

    @Inject(method = "tickGuidance", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$tickGuidanceTVMissile(CallbackInfo ci) {
        MissileEntity self = (MissileEntity) (Object) this;
        if (!(self instanceof TVMissileEntity tvMissileEntity)) {
            return;
        }
        tvMissileEntity.ywzj_rvp$manualGuidanceTick();
        ci.cancel();
    }

    @Inject(method = "tickMove", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$tickMoveTVMissileNoGravity(CallbackInfo ci) {
        MissileEntity self = (MissileEntity) (Object) this;
        if (!(self instanceof TVMissileEntity tvMissileEntity)) {
            return;
        }
        tvMissileEntity.ywzj_rvp$manualMoveTickNoGravity();
        ci.cancel();
    }
}
