package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.event.RVP_AutoCollectiveHandler;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

import java.util.function.Supplier;

/**
 * [RVP] 直升机飞行模式循环切换请求（C2S，2026-10-05）：客户端 V 键仅发意图，
 * 服务端双重校验（发送者非空 → 载具存在且为旋翼载具 → 发送者是当前驾驶员）后交
 * {@link RVP_AutoCollectiveHandler#toggleFlightMode} 执行三态循环
 * （常规 → 自动总距 → 悬停 → 常规）。与本体 Z 键悬停共存：切换前实时读本体
 * hoverMode 公开字段校准状态，零 Mixin（全部走本体 public API）。
 */
public class C2SToggleFlightMode {

    private final int vehicleId;

    public C2SToggleFlightMode(int vehicleId) {
        this.vehicleId = vehicleId;
    }

    public static void encode(C2SToggleFlightMode msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleId);
    }

    public static C2SToggleFlightMode decode(FriendlyByteBuf buf) {
        return new C2SToggleFlightMode(buf.readInt());
    }

    public static void handle(C2SToggleFlightMode msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !(player.level().getEntity(msg.vehicleId) instanceof AbstractVehicle vehicle)) {
                return;
            }
            // 仅当前驾驶员可切换（乘员/炮手/路人拒绝，防伪造包）
            if (vehicle.getDriver() != player) {
                return;
            }
            // 仅旋翼载具（固定翼无总距概念）
            if (!(vehicle instanceof RotaryWingVehicle rotaryWing)) {
                return;
            }
            RVP_AutoCollectiveHandler.toggleFlightMode(rotaryWing);
        });
    }
}
