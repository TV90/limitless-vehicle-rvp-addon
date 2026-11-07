package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：同步单台直升机当前的飞行模式。
 * <p>模式在 Z 键切换、程序设置悬停、Gunner/着舰接管时变化；玩家开始追踪载具时补发一次，
 * 供动作条在多人环境下显示与驾驶端一致的三态图标。</p>
 */
public class S2CRotaryFlightModeSync {

    /** 载具实体 ID。 */
    public int vehicleEntityId;
    /** {@code RVP_RotaryFlightMode} 序号。 */
    public int modeOrdinal;

    public S2CRotaryFlightModeSync() {}

    public S2CRotaryFlightModeSync(int vehicleEntityId, int modeOrdinal) {
        this.vehicleEntityId = vehicleEntityId;
        this.modeOrdinal = modeOrdinal;
    }

    public static void encode(S2CRotaryFlightModeSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleEntityId);
        buf.writeByte(msg.modeOrdinal);
    }

    public static S2CRotaryFlightModeSync decode(FriendlyByteBuf buf) {
        return new S2CRotaryFlightModeSync(buf.readVarInt(), buf.readUnsignedByte());
    }

    public static void handle(S2CRotaryFlightModeSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        // 公共网络类不 import 客户端类型；lambda 内全限定名只在客户端分支加载（架构测试约束）
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientRotaryFlightModeState.apply(
                        msg.vehicleEntityId, msg.modeOrdinal)));
    }
}
