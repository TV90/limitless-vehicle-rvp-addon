package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_ModuleHitboxRenderer;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 沿用已批准的调试 OBB 转发点；模块分档配色和绘制业务由独立客户端类处理。 */
@Mixin(value = VehicleRender.class, remap = false)
public class VehicleRenderRenderHitboxMixin {
    @Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$renderHitboxWithDamagedBoneColor(AbstractVehicle vehicle,
            PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        // 调用统一绘制器；未开启调试或没有模块损伤时由本体原路径处理。
        if (RVP_ModuleHitboxRenderer.render(vehicle, poseStack, bufferSource)) ci.cancel();
    }
}
