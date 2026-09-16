package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：被激光致盲（白色闪光滤镜）。
 *
 * <p>由 {@code RVP_LaserBlindService} 在激光命中累计达标时发给目标乘客。携带致盲时长
 * （tick），客户端经 DistExecutor 分发到 {@code org.ywzj.rvp.client.state.RVP_ClientLaserBlindState}
 * （全限定名引用，避免网络包直接依赖客户端类型）——全屏白色滤镜前段全亮、尾段渐隐，
 * 再次触发会刷新时长。</p>
 */
public final class S2CLaserBlind {

    private final int durationTick;

    public S2CLaserBlind(int durationTick) {
        this.durationTick = durationTick;
    }

    /** 致盲时长（tick）。 */
    public int durationTick() {
        return durationTick;
    }

    public static void encode(S2CLaserBlind msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.durationTick);
    }

    public static S2CLaserBlind decode(FriendlyByteBuf buf) {
        return new S2CLaserBlind(buf.readVarInt());
    }

    public static void handle(S2CLaserBlind msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientLaserBlindState.blind(msg.durationTick())));
        ctx.setPacketHandled(true);
    }
}
