package org.ywzj.rvp.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.vehicle.vehicle.control.InputHandler;

/**
 * HITL 视角激活期间，以及 RVP 消费右键（空爆引爆/退出视角）后的短窗口内，
 * 拦截本体 {@code handleMagnificationChange}（右键放大/倍率切换），
 * 避免引爆或退出 TV 弹视角时误触发 ywzj_vehicle 自带的右键放大。
 */
@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerMagnificationGuardMixin {

    @Inject(method = "handleMagnificationChange", at = @At("HEAD"), remap = false, cancellable = true)
    private static void ywzj_rvp$guardMagnification(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long gameTime = mc.level.getGameTime();
        if (RVP_ClientHitlState.isActive() || RVP_ClientHitlState.isRightClickGuardActive(gameTime)) {
            ci.cancel();
        }
    }
}
