package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.uav.RVP_DeployableUavService;

import java.util.function.Supplier;

public class C2SDeployDeployableUav {
    public static void encode(C2SDeployDeployableUav msg, FriendlyByteBuf buf) {
    }

    public static C2SDeployDeployableUav decode(FriendlyByteBuf buf) {
        return new C2SDeployDeployableUav();
    }

    public static void handle(C2SDeployDeployableUav msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            RVP_DeployableUavService.DeployResult result = RVP_DeployableUavService.deployLinkedUav(player);
            if (result == RVP_DeployableUavService.DeployResult.COOLDOWN
                    && player.getVehicle() instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle parent) {
                int remainingTick = RVP_DeployableUavService.getRedeployCooldownRemainingTick(parent);
                int remainingSecond = Math.max(1, (remainingTick + 19) / 20);
                player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.uav.redeploy_cooldown", remainingSecond),
                        true
                );
                return;
            }
            player.displayClientMessage(Component.translatable(switch (result) {
                case SUCCESS -> "message.ywzj_rvp.uav.deploy_success";
                case NO_PARENT_VEHICLE -> "message.ywzj_rvp.uav.not_in_vehicle";
                case NO_CONFIG -> "message.ywzj_rvp.uav.no_config";
                case INVALID_TEMPLATE -> "message.ywzj_rvp.uav.invalid_template";
                case ALREADY_DEPLOYED -> "message.ywzj_rvp.uav.already_deployed";
                case COOLDOWN -> "message.ywzj_rvp.uav.redeploy_cooldown";
                case SPAWN_FAILED -> "message.ywzj_rvp.uav.spawn_failed";
                case SEAT_NOT_ALLOWED -> "message.ywzj_rvp.uav.seat_not_allowed";
            }), true);
        });
        ctx.setPacketHandled(true);
    }
}
