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
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

@Mixin(value = VehicleRender.class, remap = false)
public abstract class VehicleRenderCockpitPassengerContextMixin<T extends AbstractVehicle> {

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$beginCockpitPassengerContext(T vehicle,
                                                       float pEntityYaw,
                                                       float pPartialTick,
                                                       PoseStack pPoseStack,
                                                       MultiBufferSource bufferSource,
                                                       int pPackedLight,
                                                       CallbackInfo ci) {
        if (!ywzj_rvp$ensureModelInstance(vehicle)) {
            ci.cancel();
            return;
        }
        RVP_CockpitPassengerRenderContext.begin(vehicle, pPartialTick);
    }

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void ywzj_rvp$endCockpitPassengerContext(T vehicle,
                                                     float pEntityYaw,
                                                     float pPartialTick,
                                                     PoseStack pPoseStack,
                                                     MultiBufferSource bufferSource,
                                                     int pPackedLight,
                                                     CallbackInfo ci) {
        RVP_CockpitPassengerRenderContext.end();
    }

    private static boolean ywzj_rvp$ensureModelInstance(AbstractVehicle vehicle) {
        if (vehicle.getVehicleModelInstance() != null) {
            return true;
        }
        VehicleDisplay<?, ?> display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null || display.getModel() == null || display.getTexture() == null) {
            return false;
        }
        vehicle.initDisplayData(display);
        return vehicle.getVehicleModelInstance() != null;
    }
}
