package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：RVP 无钳制告警（MISSILE_LAUNCH / RADAR_LOCK / RADAR_SEARCH）。
 *
 * <p>目的：绕过本体 {@code WarningReceiver.handle} 的 ±45° 俯仰角钳制。本体告警包
 * 到达客户端后若目标相对本机俯仰角超限会被直接丢弃（{@code WarningReceiver.java:45}），
 * 导致被导弹/雷达锁定却不告警。RVP 服务端补发本包，客户端收到后校验目标确为本地
 * 驾驶载具即直接写入 {@code warningReceiver.targets} —— 图标与循环音效由本体
 * {@code warningReceiver.tick()} 自动驱动，RWR 屏幕不受角度钳制。</p>
 */
public final class S2CRvpWarn {

    private final int fromEntityId;
    private final int toEntityId;
    private final WarnType warnType;
    private final String info;

    public S2CRvpWarn(int fromEntityId, int toEntityId, WarnType warnType, String info) {
        this.fromEntityId = fromEntityId;
        this.toEntityId = toEntityId;
        this.warnType = warnType;
        this.info = info;
    }

    public static void encode(S2CRvpWarn msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.fromEntityId);
        buf.writeVarInt(msg.toEntityId);
        buf.writeEnum(msg.warnType);
        buf.writeUtf(msg.info);
    }

    public static S2CRvpWarn decode(FriendlyByteBuf buf) {
        return new S2CRvpWarn(buf.readVarInt(), buf.readVarInt(), buf.readEnum(WarnType.class), buf.readUtf());
    }

    public static void handle(S2CRvpWarn msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientWarnRelay.handle(
                        msg.fromEntityId, msg.toEntityId, msg.warnType, msg.info)));
    }
}