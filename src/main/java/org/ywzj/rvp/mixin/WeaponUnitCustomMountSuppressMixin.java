package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitCustomMountSuppressMixin {

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$suppressDefaultWeaponRender(PoseStack pPoseStack,
                                                      MultiBufferSource bufferSource,
                                                      int pPackedLight,
                                                      CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (RVP_CustomMountRenderLogic.shouldSuppressDefaultWeaponDisplay(self)) {
            ci.cancel();
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "onClientFire", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$predictCustomMountAmmo(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        RVP_CustomMountRenderLogic.noteClientFire(self);
    }
}
