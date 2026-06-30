package org.ywzj.rvp.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.control.InputHandler;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerRfStabilizerMixin {

    @Inject(method = "handleVehicleAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$handleRfStabilizer(int key, int scanCode, int action, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS) {
            return;
        }
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        if (instance == null || !instance.onVehicle()) {
            return;
        }
        WeaponUnit weaponUnit = instance.getWeaponUnit();
        if (RVP_ClientGPSUtil.tryHandleModeToggleKey(key, scanCode)) {
            ci.cancel();
            return;
        }
        if (RVP_FireControlStabilizerState.tryHandleToggleKey(weaponUnit, key, scanCode)) {
            ci.cancel();
        }
    }
}
