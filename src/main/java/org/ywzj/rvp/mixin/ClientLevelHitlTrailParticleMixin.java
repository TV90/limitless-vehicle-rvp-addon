package org.ywzj.rvp.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;

@Mixin(ClientLevel.class)
public class ClientLevelHitlTrailParticleMixin {

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void ywzj_rvp$suppressHitlTrailParticle(
            ParticleOptions options, double x, double y, double z,
            double xSpeed, double ySpeed, double zSpeed, CallbackInfo ci) {
        if (RVP_ClientHitlState.shouldSuppressParticleNearActiveMissile(x, y, z)) {
            ci.cancel();
        }
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void ywzj_rvp$suppressHitlTrailParticleForced(
            ParticleOptions options, boolean force, double x, double y, double z,
            double xSpeed, double ySpeed, double zSpeed, CallbackInfo ci) {
        if (RVP_ClientHitlState.shouldSuppressParticleNearActiveMissile(x, y, z)) {
            ci.cancel();
        }
    }
}
