package org.ywzj.rvp.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.rvp.uav.RVP_LinkedUavStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

public class C2SSwitchDeployableUav {
    private static final Logger LOGGER = LogUtils.getLogger();

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
                LOGGER.info("[RVP-UAV] {} 按M：不在载具上（getVehicle={}）",
                        player.getName().getString(),
                        player.getVehicle() == null ? "null" : player.getVehicle().getClass().getSimpleName());
                return;
            }

            if (RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) {
                boolean ok = RVP_DeployableUavService.switchBackToParent(player);
                LOGGER.info("[RVP-UAV] {} 按M切回: vehicleId={} instance=true 结果={}",
                        player.getName().getString(), vehicle.getVehicleId(), ok);
                player.displayClientMessage(Component.translatable(
                        ok ? "message.ywzj_rvp.uav.switch_back_success" : "message.ywzj_rvp.uav.switch_back_failed"
                ), true);
                return;
            }

            boolean ok = RVP_DeployableUavService.switchToLinkedUav(player);
            LOGGER.info("[RVP-UAV] {} 按M切到子载具: vehicleId={} instance=false 结果={}",
                    player.getName().getString(), vehicle.getVehicleId(), ok);
            player.displayClientMessage(Component.translatable(
                    ok ? "message.ywzj_rvp.uav.switch_to_child_success" : "message.ywzj_rvp.uav.switch_to_child_failed"
            ), true);
        });
        ctx.setPacketHandled(true);
    }
}
