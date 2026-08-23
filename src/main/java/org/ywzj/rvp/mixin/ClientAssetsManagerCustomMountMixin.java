package org.ywzj.rvp.mixin;

import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.rvp.client.render.RVP_DistanceBoneHider;
import org.ywzj.rvp.client.render.RVP_LodModelManager;
import org.ywzj.rvp.client.render.RVP_StateBoneHider;
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
        // 重建状态机隐藏骨骼规则缓存
        RVP_StateBoneHider.rebindAll();
        // 重建距离 LOD 隐藏骨骼规则缓存
        RVP_DistanceBoneHider.rebindAll();
        // 重建整模型 LOD 规则缓存（烘焙 LOD 模型 + 注册 LOD 贴图）
        RVP_LodModelManager.rebindAll();
        // 重载 UI 预设（配合 /ywzj_vehicle reload 客户端热更新；
        // 服务端链路由 VehicleDataManagerMixin 的 apply TAIL 覆盖）
        org.ywzj.rvp.config.UIPresetManager.load(resourceManager);
    }
}
