package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;

/** 复用既有输入注入点，仅转发电视导弹与焦点锁定操控，不在 Mixin 中保存业务状态。 */
@Mixin(targets = "org.ywzj.vehicle.vehicle.LocalVehiclePlayer", remap = false)
public class LocalVehiclePlayerTVMissileTurnMixin {

    @Inject(method = "handlePlayerTurn", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$TVMissileTrackTurn(double pYRot, double pXRot, CallbackInfoReturnable<Boolean> cir) {
        // 调用双端安全桥，电视导弹优先，焦点拖动其次；未处理时继续本体原有输入。
        if (RVP_ClientActionsAccess.handleVehiclePlayerTurn(pYRot, pXRot)) {
            cir.setReturnValue(true);
        }
    }
}
