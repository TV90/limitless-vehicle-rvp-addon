package org.ywzj.rvp.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.hitl.RVP_ClientHitlRemoteView;
import org.ywzj.rvp.client.hitl.RVP_HitlClientLightAccess;

/** 复用私有光照应用，并区分身体区域 forget 与远程观察租约。 */
@Mixin(ClientPacketListener.class)
public abstract class HitlRemoteClientPacketMixin implements RVP_HitlClientLightAccess {
    /** 当前连接的客户端世界。 */
    @Shadow private ClientLevel level;

    @Invoker("applyLightData")
    public abstract void rvp$applyRemoteLight(int x, int z, ClientboundLightUpdatePacketData data);

    @Invoker("enableChunkLight")
    public abstract void rvp$enableRemoteLight(LevelChunk chunk, int x, int z);

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
    private void rvp$keepRemoteLight(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        // 网络线程先让原版 PacketUtils 排队；只在主线程处理业务，避免提前操作世界。
        if (net.minecraft.client.Minecraft.getInstance().isSameThread()
                && RVP_ClientHitlRemoteView.forgetNative(level, packet.getX(), packet.getZ())) ci.cancel();
    }
}
