package org.ywzj.rvp.mixin;



import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;

import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;

import org.spongepowered.asm.mixin.injection.At;

import org.spongepowered.asm.mixin.injection.Inject;

import org.spongepowered.asm.mixin.injection.Redirect;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.ywzj.rvp.client.seeker.RVP_SeekerVanillaHudCompat;

import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;

import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;

import org.ywzj.vehicle.client.render.util.GuiHelper;



/**

 * Replaces vanilla seeker FOV rings with the RVP screen-centre HUD while keeping radar lock squares.

 */

@Mixin(value = VehicleAimAtOverlay.class, remap = false)

public class VehicleSeekerHudMixin {



    @Redirect(

            method = "render",

            at = @At(

                    value = "INVOKE",

                    target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V"

            ),

            require = 0,

            remap = false

    )

    private void ywzj_rvp$redirectSeekerCircle(

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



    @Inject(

            method = "render",

            at = @At(

                    value = "INVOKE",

                    target = "Lorg/ywzj/vehicle/client/gui/VehicleScopeOverlay;renderAimLockTarget(Lnet/minecraft/client/gui/GuiGraphics;F)V",

                    shift = At.Shift.AFTER

            ),

            require = 0,

            remap = false

    )

    private void ywzj_rvp$afterVanillaLockTarget(
            ForgeGui gui,
            GuiGraphics guiGraphics,
            float partialTick,
            int screenWidth,
            int screenHeight,
            CallbackInfo ci
    ) {

        RVP_SeekerVanillaHudCompat.renderSarhRadarLockFrame(guiGraphics, partialTick);

    }

}


