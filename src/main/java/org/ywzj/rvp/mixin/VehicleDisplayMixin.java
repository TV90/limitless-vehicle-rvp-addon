package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.api.animation.IAnimationInstance;
import org.ywzj.vehicle.client.render.animation.context.VehicleContext;
import org.ywzj.vehicle.client.render.animation.controller.AnimationController;
import org.ywzj.vehicle.client.render.animation.runner.SwitchableRunner;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

@Mixin(value = VehicleDisplay.class, remap = false)
public class VehicleDisplayMixin {

    @Inject(
            method = "createAnimationInstance",
            at = @At("RETURN"),
            remap = false
    )
    private <E extends AbstractVehicle, CTX extends VehicleContext<E>> void ywzj_rvp$addRadarSwitchableRunners(
            E entity, CallbackInfoReturnable<IAnimationInstance<CTX>> cir) {
        IAnimationInstance<CTX> result = cir.getReturnValue();
        if (result == null) return;

        CTX context = result.getContext();
        VehicleDisplay<E, CTX> self = (VehicleDisplay<E, CTX>) (Object) this;
        AnimationController<CTX> controller = self.getAnimationController();
        if (controller == null) return;

        for (var entry : controller.getSwitchableAnimations().entrySet()) {
            String key = entry.getKey();
            if (context.getSwitchableRunner(key) != null) continue;

            String partId = entry.getValue().getPartId();
            String animName = entry.getValue().getAnimation();
            boolean invert = entry.getValue().isInvert();

            BedrockAnimation anim = context.getAnimation(animName);
            if (anim == null) continue;

            entity.getPartUnit(partId).ifPresent(part -> {
                if (part instanceof RadarUnit) {
                    context.addSwitchableRunner(key, new SwitchableRunner(
                            new RadarUnitSwitchableAdapter((RadarUnit) part), anim, invert));
                }
            });
        }
    }
}
