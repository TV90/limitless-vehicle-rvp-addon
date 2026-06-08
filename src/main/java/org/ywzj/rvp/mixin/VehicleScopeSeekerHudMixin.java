package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.seeker.RVP_SeekerVanillaHudCompat;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.client.render.util.GuiHelper;

@Mixin(value = VehicleScopeOverlay.class, remap = false)
public class VehicleScopeSeekerHudMixin {

    @Redirect(
            method = "renderAimLockTarget",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V"
            ),
            require = 0,
            remap = false
    )
    private static void ywzj_rvp$redirectScopeSeekerCircle(
            PoseStack poseStack,
            float x,
            float y,
            float radius,
            int color,
            float thickness,
            float start,
            float end
    ) {
        if (RVP_SeekerVanillaHudCompat.shouldSuppressVanillaSeekerFov(radius)
                || RVP_SeekerVanillaHudCompat.shouldSuppressVanillaSeekerLockCircle(radius)) {
            return;
        }
        GuiHelper.drawCircle(poseStack, x, y, radius, color, thickness, start, end);
    }

    @Inject(method = "renderAimLockTarget", at = @At("TAIL"), remap = false)
    private static void ywzj_rvp$afterScopeLockTarget(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
        RVP_SeekerVanillaHudCompat.renderSarhRadarLockFrame(guiGraphics, partialTick);
    }
}
