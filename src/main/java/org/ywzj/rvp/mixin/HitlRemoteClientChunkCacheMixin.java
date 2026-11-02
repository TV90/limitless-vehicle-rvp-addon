package org.ywzj.rvp.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.hitl.RVP_ClientHitlRemoteView;
import java.util.function.Consumer;

/** 原版固定槽位之外的受控缓存兜底；业务与共享状态全部在客户端服务中。 */
@Mixin(ClientChunkCache.class)
public abstract class HitlRemoteClientChunkCacheMixin {
    /** 当前缓存所属的实际客户端世界。 */
    @Shadow @Final private ClientLevel level;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("RETURN"), cancellable = true)
    private void rvp$lookupRemote(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<LevelChunk> cir) {
        cir.setReturnValue(RVP_ClientHitlRemoteView.lookup(level, x, z, cir.getReturnValue()));
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"), cancellable = true)
    private void rvp$shareNativeChunk(int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer, CallbackInfoReturnable<LevelChunk> cir) {
        cir.setReturnValue(RVP_ClientHitlRemoteView.afterNativePacket(level, x, z, buffer, tag, consumer, cir.getReturnValue()));
    }

    @Inject(method = "replaceBiomes", at = @At("HEAD"), cancellable = true)
    private void rvp$remoteBiomes(int x, int z, FriendlyByteBuf buffer, CallbackInfo ci) {
        if (RVP_ClientHitlRemoteView.replaceRemoteBiomes(level, x, z, buffer)) ci.cancel();
    }
}
