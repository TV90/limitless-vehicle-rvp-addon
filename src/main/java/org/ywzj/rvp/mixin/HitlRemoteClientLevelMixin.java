package org.ywzj.rvp.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.hitl.RVP_ClientHitlRemoteView;

/** 防止原版槽位滚动把仍被远程窗口使用的区块注销。 */
@Mixin(ClientLevel.class)
public abstract class HitlRemoteClientLevelMixin {
    @Inject(method = "unload", at = @At("HEAD"), cancellable = true)
    private void rvp$keepObservedChunk(LevelChunk chunk, CallbackInfo ci) {
        if (RVP_ClientHitlRemoteView.holds((ClientLevel) (Object) this, chunk.getPos().x, chunk.getPos().z)) ci.cancel();
    }
}
