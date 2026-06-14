package org.ywzj.rvp.mixin;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.gui.RVP_RocketCcipOverlay;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.util.RenderHelper;
import org.slf4j.Logger;

@Mixin(value = VehicleAimAtOverlay.class, remap = false)
public class VehicleCrossHairOverlayMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean ywzj_rvp$lastReticleReplaceState;

    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = false)
    private void ywzj_rvp$drawRocketCcip(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        if (!RVP_RocketCcipOverlay.isBallisticRocketActive()) {
            return;
        }
        Vec3 hitPos = RVP_RocketCcipOverlay.getCurrentScreenHitPos();
        if (hitPos == null || hitPos.z < 0) {
            return;
        }
        if (!Double.isFinite(hitPos.x) || !Double.isFinite(hitPos.y) || !Double.isFinite(hitPos.z)) {
            return;
        }
        double x = Math.max(0.0, Math.min(screenWidth, hitPos.x));
        double y = Math.max(0.0, Math.min(screenHeight, hitPos.y));
        RVP_RocketCcipOverlay.drawAtScreen(guiGraphics, x, y, partialTick, 32);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/util/RenderHelper;drawCross(Lnet/minecraft/client/gui/GuiGraphics;IIII)V"
            ),
            require = 0,
            remap = false
    )
    private void ywzj_rvp$replaceRocketReticleCross(
            GuiGraphics guiGraphics,
            int x,
            int y,
            int size,
            int color
    ) {
        boolean replace = RVP_RocketCcipOverlay.shouldReplaceReticle();
        if (replace != ywzj_rvp$lastReticleReplaceState) {
            ywzj_rvp$lastReticleReplaceState = replace;
            LOGGER.info("[RVP][RocketCCIP] reticle_replace={}", replace);
        }
        if (replace) {
            RVP_RocketCcipOverlay.draw(guiGraphics, Minecraft.getInstance().getFrameTime());
            return;
        }
        RenderHelper.drawCross(guiGraphics, x, y, size, color);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/util/RenderHelper;drawSquare(Lnet/minecraft/client/gui/GuiGraphics;IIII)V"
            ),
            require = 0,
            remap = false
    )
    private void ywzj_rvp$replaceRocketReticleSquare(
            GuiGraphics guiGraphics,
            int x,
            int y,
            int size,
            int color
    ) {
        boolean replace = RVP_RocketCcipOverlay.shouldReplaceReticle();
        if (replace != ywzj_rvp$lastReticleReplaceState) {
            ywzj_rvp$lastReticleReplaceState = replace;
            LOGGER.info("[RVP][RocketCCIP] reticle_replace={}", replace);
        }
        if (replace) {
            RVP_RocketCcipOverlay.draw(guiGraphics, Minecraft.getInstance().getFrameTime());
            return;
        }
        RenderHelper.drawSquare(guiGraphics, x, y, size, color);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/util/RenderHelper;drawCrossDiagonal(Lnet/minecraft/client/gui/GuiGraphics;IIIII)V"
            ),
            require = 0,
            remap = false
    )
    private void ywzj_rvp$replaceRocketReticleCrossDiagonal(
            GuiGraphics guiGraphics,
            int x,
            int y,
            int size,
            int thickness,
            int color
    ) {
        boolean replace = RVP_RocketCcipOverlay.shouldReplaceReticle();
        if (replace != ywzj_rvp$lastReticleReplaceState) {
            ywzj_rvp$lastReticleReplaceState = replace;
            LOGGER.info("[RVP][RocketCCIP] reticle_replace={}", replace);
        }
        if (replace) {
            RVP_RocketCcipOverlay.draw(guiGraphics, Minecraft.getInstance().getFrameTime());
            return;
        }
        RenderHelper.drawCrossDiagonal(guiGraphics, x, y, size, thickness, color);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V",
                    ordinal = 0
            ),
            require = 0,
            remap = false
    )
    private void ywzj_rvp$replaceRocketAimCircle(
            PoseStack poseStack,
            float x,
            float y,
            float radius,
            int color,
            float thickness,
            float start,
            float end
    ) {
        if (RVP_RocketCcipOverlay.isBallisticRocketActive()) {
            return;
        }
        GuiHelper.drawCircle(poseStack, x, y, radius, color, thickness, start, end);
    }
}
