package org.ywzj.rvp.mixin;

import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.rvp.client.resource.vehicle.RVP_DisplayBackendUtil;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;

@Mixin(value = ClientAssetsManager.class, remap = false)
public class ClientAssetsManagerCustomMountMixin {

    @Inject(method = "reload", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$clearRenderCaches(ResourceManager resourceManager, CallbackInfo ci) {
        RVP_CustomMountRenderLogic.clearModelCache();
        // 全部 display 构建完成后重建 backend / no-cull 骨骼绑定缓存，
        // 保证渲染热路径只做纯缓存读取（不触发遍历/反射）。
        RVP_DisplayBackendUtil.rebindAll();
    }
}
