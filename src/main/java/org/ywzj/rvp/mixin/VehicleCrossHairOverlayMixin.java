package org.ywzj.rvp.mixin;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.gui.RVP_RocketCcipOverlay;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.util.RenderHelper;
import org.slf4j.Logger;

@Mixin(value = VehicleAimAtOverlay.class, remap = false)
public class VehicleCrossHairOverlayMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean ywzj_rvp$lastReticleReplaceState;

    @Inject(method = "render", at = @At("HEAD"), require = 0, remap = false)
    private void ywzj_rvp$beginCcipFrame(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        RVP_RocketCcipOverlay.beginRenderFrame();
        RVP_RocketCcipOverlay.dbgNoteRender();
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = false)
    private void ywzj_rvp$drawRocketCcip(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        // 2026-10-22：默认不替换（用户使用本体样式的 CCIP 准星），给本体让路直接 return。
        if (!RVP_RocketCcipOverlay.isLegacyIconReplaceEnabled()) {
            RVP_RocketCcipOverlay.dbgNoteTail("本体样式(不替换)");
            return;
        }
        // 锚点路径（drawCrosshair 重定向内）已画过就不重复画；
        // 该重定向只在「本体有准星样式」时触发，无样式的站仍走这里的世界坐标投影兜底。
        if (RVP_RocketCcipOverlay.drewAtAnchorThisFrame()) {
            RVP_RocketCcipOverlay.dbgNoteTail("anchor已画(跳过)");
            return;
        }
        if (!RVP_RocketCcipOverlay.isBallisticRocketActive()) {
            RVP_RocketCcipOverlay.dbgNoteTail("门控false");
            return;
        }
        Vec3 hitPos = RVP_RocketCcipOverlay.getCurrentScreenHitPos();
        if (hitPos == null) {
            RVP_RocketCcipOverlay.dbgNoteTail("hitPos=null");
            return;
        }
        if (hitPos.z < 0) {
            RVP_RocketCcipOverlay.dbgNoteTail("hitPos.z<0(相机背后)");
            return;
        }
        if (!Double.isFinite(hitPos.x) || !Double.isFinite(hitPos.y) || !Double.isFinite(hitPos.z)) {
            RVP_RocketCcipOverlay.dbgNoteTail("非有限坐标");
            return;
        }
        RVP_RocketCcipOverlay.dbgNoteTail("TAIL绘制");
        double x = Math.max(0.0, Math.min(screenWidth, hitPos.x));
        double y = Math.max(0.0, Math.min(screenHeight, hitPos.y));
        RVP_RocketCcipOverlay.drawAtScreen(guiGraphics, x, y, partialTick, 32);
    }

    @Redirect(
            method = "drawCrosshair",
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
        RVP_RocketCcipOverlay.dbgNoteCrosshair("REDIRECT", replace);
        if (replace != ywzj_rvp$lastReticleReplaceState) {
            ywzj_rvp$lastReticleReplaceState = replace;
            // 火箭 CCIP 准星替换探针（开关：/rvpdebug flags ccip）
            if (RVP_DebugFlags.CCIP.isEnabled()) {
                LOGGER.info("[RVP][RocketCCIP] reticle_replace={}", replace);
            }
        }
        if (replace) {
            // 锚点即本体已摆好的弹着预测点：在本帧尚未画过时补画 CCIP 图标（隐藏与绘制原子化）
            if (!RVP_RocketCcipOverlay.drewAtAnchorThisFrame()) {
                RVP_RocketCcipOverlay.drawAtCrosshairAnchor(guiGraphics, 32);
            }
            return;
        }
        RenderHelper.drawCross(guiGraphics, x, y, size, color);
    }

    @Redirect(
            method = "drawCrosshair",
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
        RVP_RocketCcipOverlay.dbgNoteCrosshair("REDIRECT", replace);
        if (replace != ywzj_rvp$lastReticleReplaceState) {
            ywzj_rvp$lastReticleReplaceState = replace;
            // 火箭 CCIP 准星替换探针（开关：/rvpdebug flags ccip）
            if (RVP_DebugFlags.CCIP.isEnabled()) {
                LOGGER.info("[RVP][RocketCCIP] reticle_replace={}", replace);
            }
        }
        if (replace) {
            // 锚点即本体已摆好的弹着预测点：在本帧尚未画过时补画 CCIP 图标（隐藏与绘制原子化）
            if (!RVP_RocketCcipOverlay.drewAtAnchorThisFrame()) {
                RVP_RocketCcipOverlay.drawAtCrosshairAnchor(guiGraphics, 32);
            }
            return;
        }
        RenderHelper.drawSquare(guiGraphics, x, y, size, color);
    }

    @Redirect(
            method = "drawCrosshair",
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
        RVP_RocketCcipOverlay.dbgNoteCrosshair("REDIRECT", replace);
        if (replace != ywzj_rvp$lastReticleReplaceState) {
            ywzj_rvp$lastReticleReplaceState = replace;
            // 火箭 CCIP 准星替换探针（开关：/rvpdebug flags ccip）
            if (RVP_DebugFlags.CCIP.isEnabled()) {
                LOGGER.info("[RVP][RocketCCIP] reticle_replace={}", replace);
            }
        }
        if (replace) {
            // 锚点即本体已摆好的弹着预测点：在本帧尚未画过时补画 CCIP 图标（隐藏与绘制原子化）
            if (!RVP_RocketCcipOverlay.drewAtAnchorThisFrame()) {
                RVP_RocketCcipOverlay.drawAtCrosshairAnchor(guiGraphics, 32);
            }
            return;
        }
        RenderHelper.drawCrossDiagonal(guiGraphics, x, y, size, thickness, color);
    }

    @Redirect(
            method = "lambda$render$2",
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
        if (RVP_RocketCcipOverlay.isLegacyIconReplaceEnabled() && RVP_RocketCcipOverlay.isBallisticRocketActive()) {
            return;
        }
        GuiHelper.drawCircle(poseStack, x, y, radius, color, thickness, start, end);
    }
}
