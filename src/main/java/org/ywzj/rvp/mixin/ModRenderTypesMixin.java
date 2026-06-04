package org.ywzj.rvp.mixin;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.vehicle.client.render.ModRenderTypes;

@Mixin(value = ModRenderTypes.class, remap = false)
public class ModRenderTypesMixin {
    @Inject(method = "polyMeshTransparent", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$polyMeshTransparentCulled(ResourceLocation texture, CallbackInfoReturnable<RenderType> cir) {
        cir.setReturnValue(RVP_RenderTypes.polyMeshTransparent(texture));
    }
}
