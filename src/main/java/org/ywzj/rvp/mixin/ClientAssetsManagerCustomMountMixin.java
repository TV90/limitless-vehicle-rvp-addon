package org.ywzj.rvp.mixin;

import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;

@Mixin(value = ClientAssetsManager.class, remap = false)
public class ClientAssetsManagerCustomMountMixin {

    @Inject(method = "reload", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$clearCustomMountModelCache(ResourceManager resourceManager, CallbackInfo ci) {
        RVP_CustomMountRenderLogic.clearModelCache();
    }
}
