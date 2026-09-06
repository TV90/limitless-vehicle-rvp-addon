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
 *
 * <p>{@code audible}（2026-09-06 新增）：是否播一次性告警音。RADAR_SEARCH 的响声节奏
 * 由 {@code RVP_WarnRelayService} 按敌方雷达实际扫描周期调度（首次接触立即响 + 每轮
 * 扫描响一声，对标本体被删 mixin 前的 100ms 寿命语义），其余 5 tick 轮询包
 * {@code audible=false} 仅刷新 targets，保 RWR 图标/文字常亮不闪。
 * RADAR_LOCK / MISSILE_LAUNCH 不受此字段影响（循环音由本体 tick() 依 targets 驱动）。</p>
 */
public final class S2CRvpWarn {

    private final int fromEntityId;
    private final int toEntityId;
    private final WarnType warnType;
    private final String info;
    /** 是否播一次性告警音（仅 RADAR_SEARCH 消费）：false 时只写 targets 不播音。 */
    private final boolean audible;

    /** 兼容旧调用点（MISSILE_LAUNCH/RADAR_LOCK 等）：默认播报（循环音类型实际不消费此标志）。 */
    public S2CRvpWarn(int fromEntityId, int toEntityId, WarnType warnType, String info) {
        this(fromEntityId, toEntityId, warnType, info, true);
    }

    public S2CRvpWarn(int fromEntityId, int toEntityId, WarnType warnType, String info, boolean audible) {
        this.fromEntityId = fromEntityId;
        this.toEntityId = toEntityId;
        this.warnType = warnType;
        this.info = info;
        this.audible = audible;
    }

    public static void encode(S2CRvpWarn msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.fromEntityId);
        buf.writeVarInt(msg.toEntityId);
        buf.writeEnum(msg.warnType);
        buf.writeUtf(msg.info);
        buf.writeBoolean(msg.audible);
    }

    public static S2CRvpWarn decode(FriendlyByteBuf buf) {
        return new S2CRvpWarn(buf.readVarInt(), buf.readVarInt(), buf.readEnum(WarnType.class),
                buf.readUtf(), buf.readBoolean());
    }

    public static void handle(S2CRvpWarn msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientWarnRelay.handle(
                        msg.fromEntityId, msg.toEntityId, msg.warnType, msg.info, msg.audible)));
    }
}
