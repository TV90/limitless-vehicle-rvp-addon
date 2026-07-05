package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

public class C2SSwitchDeployableUav {
    public static void encode(C2SSwitchDeployableUav msg, FriendlyByteBuf buf) {
    }

    public static C2SSwitchDeployableUav decode(FriendlyByteBuf buf) {
        return new C2SSwitchDeployableUav();
    }

    public static void handle(C2SSwitchDeployableUav msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav.not_in_vehicle"), true);
                return;
            }

            if (vehicle instanceof AbstractVehicleLinkedUavExt ext && ext.ywzj_rvp$isDeployableUavInstance()) {
                boolean ok = RVP_DeployableUavService.switchBackToParent(player);
                player.displayClientMessage(Component.translatable(
                        ok ? "message.ywzj_rvp.uav.switch_back_success" : "message.ywzj_rvp.uav.switch_back_failed"
                ), true);
                return;
            }

            boolean ok = RVP_DeployableUavService.switchToLinkedUav(player);
            player.displayClientMessage(Component.translatable(
                    ok ? "message.ywzj_rvp.uav.switch_to_child_success" : "message.ywzj_rvp.uav.switch_to_child_failed"
            ), true);
        });
        ctx.setPacketHandled(true);
    }
}
