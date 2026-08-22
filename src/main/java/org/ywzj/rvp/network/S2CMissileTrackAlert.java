package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：导弹跟踪目标的告警（驱动血条上方提示文案 + IR/AIR 告警音效）。
 *
 * <p>由 {@code RVP_MissileEntity} 在弹载导引头锁定目标时周期性发送给目标的跟踪玩家。
 * 携带 {@code targetEntityId}，客户端仅当本地驾驶载具就是被跟踪目标时才显示告警
 * （避免附近玩家误报）。客户端逻辑经 DistExecutor 分发到
 * {@code org.ywzj.rvp.client.state.RVP_ClientMissileTrackAlert}（全限定名引用，避免网络包
 * 直接依赖客户端类型）。</p>
 */
public final class S2CMissileTrackAlert {

    /** 制导类型码：1=ARH（箔条提示），2=AIR（红外提示），3=IR（红外提示），
     *  4=HITL_TV（电视制导提示，音效同红外），5=LASER（激光照射提示）。 */
    public static final byte TYPE_ARH = 1;
    public static final byte TYPE_AIR = 2;
    public static final byte TYPE_IR = 3;
    public static final byte TYPE_HITL_TV = 4;
    public static final byte TYPE_LASER = 5;

    private final int missileEntityId;
    private final int targetEntityId;
    private final byte guidanceType;

    public S2CMissileTrackAlert(int missileEntityId, int targetEntityId, byte guidanceType) {
        this.missileEntityId = missileEntityId;
        this.targetEntityId = targetEntityId;
        this.guidanceType = guidanceType;
    }

    public static void encode(S2CMissileTrackAlert msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.missileEntityId);
        buf.writeVarInt(msg.targetEntityId);
        buf.writeByte(msg.guidanceType);
    }

    public static S2CMissileTrackAlert decode(FriendlyByteBuf buf) {
        return new S2CMissileTrackAlert(buf.readVarInt(), buf.readVarInt(), buf.readByte());
    }

    public static void handle(S2CMissileTrackAlert msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientMissileTrackAlert.handle(
                        msg.missileEntityId, msg.targetEntityId, msg.guidanceType)));
        ctx.setPacketHandled(true);
    }
}
