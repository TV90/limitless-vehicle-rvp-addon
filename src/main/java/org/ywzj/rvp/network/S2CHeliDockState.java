package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：同步当前玩家的直升机着舰/接管控制锁定状态。
 * <p>客户端用于本地运动输入预清零（与服务端 {@code ControlUnitMixin} 拦截双保险，防预测打架）。</p>
 */
public class S2CHeliDockState {

    public boolean controlLocked;

    public S2CHeliDockState() {
    }

    public S2CHeliDockState(boolean controlLocked) {
        this.controlLocked = controlLocked;
    }

    public static void encode(S2CHeliDockState msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.controlLocked);
    }

    public static S2CHeliDockState decode(FriendlyByteBuf buf) {
        return new S2CHeliDockState(buf.readBoolean());
    }

    public static void handle(S2CHeliDockState msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        // 架构规约：network 包新消息不 import client 类，经 DistExecutor 全限定内联分发（客户端分支才加载）
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientHeliDockState.setControlLocked(msg.controlLocked)));
    }
}
