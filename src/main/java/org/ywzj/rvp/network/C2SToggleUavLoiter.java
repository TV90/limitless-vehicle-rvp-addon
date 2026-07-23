package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：手动切换无人机盘旋状态。
 * <p>可在驾驶无人机或母车时发送，切换关联无人机的盘旋开关。</p>
 */
public class C2SToggleUavLoiter {

    public static void encode(C2SToggleUavLoiter msg, FriendlyByteBuf buf) {
    }

    public static C2SToggleUavLoiter decode(FriendlyByteBuf buf) {
        return new C2SToggleUavLoiter();
    }

    public static void handle(C2SToggleUavLoiter msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.not_in_vehicle"), true);
                return;
            }

            AbstractVehicle uav = resolveTargetUav(vehicle);
            if (uav == null) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.no_uav"), true);
                return;
            }

            UUID uavUuid = uav.getUUID();
            if (RVP_UavLoiterManager.isLoitering(uavUuid)) {
                RVP_UavLoiterManager.disable(uavUuid);
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.disabled"), true);
            } else {
                RVP_LoiterConfig config = resolveLoiterConfig(vehicle, uav);
                if (!config.isConfigured()) {
                    player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.no_config"), true);
                    return;
                }
                // 确定圆心：有母车则跟随母车，否则用当前位置
                AbstractVehicle parent = resolveParent(vehicle, uav);
                if (parent != null) {
                    RVP_UavLoiterManager.enableFollowParent(
                            uavUuid, parent.getUUID(),
                            config.loiterRadius(), config.loiterAltitudeOffset(),
                            parent.getX(), parent.getY(), parent.getZ()
                    );
                } else {
                    // 无母车：以当前位置为固定圆心（AC130 等场景）
                    RVP_UavLoiterManager.enableMarkedCenter(
                            uavUuid, uav.position(),
                            config.loiterRadius(), uav.getY() + config.loiterAltitudeOffset()
                    );
                }
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.enabled"), true);
            }
        });
        ctx.setPacketHandled(true);
    }

    /** 解析目标无人机：当前驾驶的是无人机则返回它，否则查关联的子无人机。 */
    private static AbstractVehicle resolveTargetUav(AbstractVehicle vehicle) {
        if (vehicle instanceof AbstractVehicleLinkedUavExt ext && ext.ywzj_rvp$isDeployableUavInstance()) {
            return vehicle;
        }
        Optional<AbstractVehicle> child = RVP_DeployableUavService.getLinkedChild(vehicle);
        return child.orElse(null);
    }

    /** 解析母车：当前驾驶的是母车则返回它，否则查关联的母车。 */
    private static AbstractVehicle resolveParent(AbstractVehicle vehicle, AbstractVehicle uav) {
        if (vehicle instanceof AbstractVehicleLinkedUavExt ext && !ext.ywzj_rvp$isDeployableUavInstance()) {
            return vehicle;
        }
        Optional<AbstractVehicle> parent = RVP_DeployableUavService.getLinkedParent(uav);
        return parent.orElse(null);
    }

    /** 获取盘旋配置。优先查母车，其次查载具自身。 */
    private static RVP_LoiterConfig resolveLoiterConfig(AbstractVehicle vehicle, AbstractVehicle uav) {
        AbstractVehicle parent = resolveParent(vehicle, uav);
        if (parent != null) {
            RVP_LoiterConfig parentConfig = RVP_LoiterConfigCache.get(parent.getVehicleId());
            if (parentConfig.isConfigured()) {
                return parentConfig;
            }
        }
        // 回退到载具自身配置（AC130 等通用场景）
        return RVP_LoiterConfigCache.get(uav.getVehicleId());
    }
}
