package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

@Mixin(value = VehicleRender.class, remap = false)
public class VehicleRenderCustomMountMixin {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/entity/vehicle/AbstractVehicle;getPartUnits()Ljava/util/List;"
            ),
            remap = false
    )
    private void ywzj_rvp$renderCustomMounts(AbstractVehicle vehicle,
                                            float pEntityYaw,
                                            float pPartialTick,
                                            PoseStack pPoseStack,
                                            MultiBufferSource bufferSource,
                                            int pPackedLight,
                                            CallbackInfo ci) {
        VehicleBedrockModel model = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId())
                .map(display -> display.getModel())
                .orElse(null);
        if (model == null) {
            return;
        }
        RVP_CustomMountRenderLogic.render(vehicle, model, pPoseStack, bufferSource, pPackedLight);
    }
}
