package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.RVP_HbmClientCompat;

@Mixin(LevelRenderer.class)
public class LevelRendererHbmCloudCompatMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ywzj_rvp$renderLateHbmCloudlets(PoseStack pPoseStack, float pPartialTick, long pFinishNanoTime,
                                                 boolean pRenderBlockOutline, Camera pCamera,
                                                 GameRenderer pGameRenderer, LightTexture pLightTexture,
                                                 Matrix4f pProjectionMatrix, CallbackInfo ci) {
        RVP_HbmClientCompat.renderLateTorexCloudlets(pCamera, pPartialTick);
    }
}
