package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RvpClientTVMissileState;
import org.ywzj.rvp.entity.weapon.TVMissileEntity;
import org.ywzj.vehicle.client.render.entity.weapon.MissileEntityRenderer;
import org.ywzj.vehicle.entity.weapon.MissileEntity;

@Mixin(value = MissileEntityRenderer.class, remap = false)
public class MissileEntityRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$hideSelfInTVMissileView(MissileEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        if (!(entity instanceof TVMissileEntity)) {
            return;
        }
        if (!RvpClientTVMissileState.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.getCameraType().isFirstPerson()
                && entity.getId() == RvpClientTVMissileState.getActiveMissileId()) {
            ci.cancel();
        }
    }
}
