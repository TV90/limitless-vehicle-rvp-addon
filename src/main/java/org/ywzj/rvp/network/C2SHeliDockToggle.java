package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.helidock.RVP_HeliDockManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：P 键切换直升机着舰流程（接近/着舰/起飞）。
 * <p>服务端校验：玩家驾驶旋翼载具（operation 校验经 {@code getVehicle()} 即骑乘事实）。</p>
 */
public class C2SHeliDockToggle {

    public C2SHeliDockToggle() {
    }

    public static void encode(C2SHeliDockToggle msg, FriendlyByteBuf buf) {
    }

    public static C2SHeliDockToggle decode(FriendlyByteBuf buf) {
        return new C2SHeliDockToggle();
    }

    public static void handle(C2SHeliDockToggle msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!(player.getVehicle() instanceof RotaryWingVehicle heli)) {
                return;
            }
            // 仅驾驶员可触发（控制锁定态下玩家仍在直升机座位上，骑乘事实即权限）
            Entity driver = heli.getDriver();
            if (driver != player) {
                return;
            }
            RVP_HeliDockManager.toggle(player, heli);
        });
        ctx.setPacketHandled(true);
    }
}
