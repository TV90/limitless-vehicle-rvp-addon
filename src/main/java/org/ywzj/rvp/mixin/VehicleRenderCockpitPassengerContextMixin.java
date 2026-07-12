package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_CockpitPassengerRenderContext;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

@Mixin(value = VehicleRender.class, remap = false)
public abstract class VehicleRenderCockpitPassengerContextMixin<T extends AbstractVehicle> {

    @OnlyIn(Dist.CLIENT)
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/resource/vehicle/VehicleBedrockModel;renderSpecialBones(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIZ)V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void ywzj_rvp$beforeRenderCockpit(T vehicle, float pEntityYaw, float pPartialTick,
                                              PoseStack pPoseStack, MultiBufferSource bufferSource,
                                              int pPackedLight, CallbackInfo ci) {
        RVP_CockpitPassengerRenderContext.begin(vehicle, pPartialTick);
    }

    @OnlyIn(Dist.CLIENT)
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/resource/vehicle/VehicleBedrockModel;renderSpecialBones(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIZ)V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void ywzj_rvp$afterRenderCockpit(T vehicle, float pEntityYaw, float pPartialTick,
                                             PoseStack pPoseStack, MultiBufferSource bufferSource,
                                             int pPackedLight, CallbackInfo ci) {
        RVP_CockpitPassengerRenderContext.end();
    }
}
