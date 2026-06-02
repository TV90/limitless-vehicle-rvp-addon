package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.render.RvpRenderTypes;

@Mixin(value = BedrockModelRenderTypes.class, remap = false)
public class BedrockModelRenderTypesMixin {
    @Inject(method = "polyMeshCutout", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$polyMeshCutoutCulled(ResourceLocation texture, CallbackInfoReturnable<RenderType> cir) {
        cir.setReturnValue(RvpRenderTypes.polyMeshCutout(texture));
    }
}
