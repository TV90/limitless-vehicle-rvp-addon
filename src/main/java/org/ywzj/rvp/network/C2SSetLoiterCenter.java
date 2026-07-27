package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
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
 * 客户端 → 服务端：在战术地图上标记盘旋圆心。
 */
public class C2SSetLoiterCenter {

    private double x;
    private double y;
    private double z;

    public C2SSetLoiterCenter() {}

    public C2SSetLoiterCenter(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static void encode(C2SSetLoiterCenter msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
    }

    public static C2SSetLoiterCenter decode(FriendlyByteBuf buf) {
        return new C2SSetLoiterCenter(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(C2SSetLoiterCenter msg, Supplier<NetworkEvent.Context> ctxSupplier) {
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

            // 解析目标无人机：当前驾驶的是无人机则返回它，否则查关联的子无人机
            AbstractVehicle uav = resolveTargetUav(vehicle);
            if (uav == null) {
                return;
            }

            // 获取盘旋配置
            RVP_LoiterConfig config = resolveLoiterConfig(vehicle, uav);
            if (!config.isConfigured()) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.no_config"), true);
                return;
            }

            Vec3 center = new Vec3(msg.x, msg.y, msg.z);
            // 目标高度：切换盘旋时维持当前高度，避免自动爬升
            double altitude = uav.getY();
            UUID uavUuid = uav.getUUID();

            if (RVP_UavLoiterManager.isLoitering(uavUuid)) {
                // 已在盘旋：更新圆心
                RVP_UavLoiterManager.updateCenter(uavUuid, center, altitude);
            } else {
                // 未盘旋：以标记点为固定圆心激活
                RVP_UavLoiterManager.enableMarkedCenter(uavUuid, center, config.loiterRadius(), altitude);
            }
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.center_set"), true);
        });
        ctx.setPacketHandled(true);
    }

    private static AbstractVehicle resolveTargetUav(AbstractVehicle vehicle) {
        if (vehicle instanceof AbstractVehicleLinkedUavExt ext && ext.ywzj_rvp$isDeployableUavInstance()) {
            return vehicle;
        }
        // AC130 等自身带盘旋配置的固定翼载具，直接对自身盘旋
        RVP_LoiterConfig selfConfig = RVP_LoiterConfigCache.get(vehicle.getVehicleId());
        if (selfConfig.isConfigured()) {
            return vehicle;
        }
        Optional<AbstractVehicle> child = RVP_DeployableUavService.getLinkedChild(vehicle);
        return child.orElse(null);
    }

    private static RVP_LoiterConfig resolveLoiterConfig(AbstractVehicle vehicle, AbstractVehicle uav) {
        if (vehicle == uav) {
            return RVP_LoiterConfigCache.get(uav.getVehicleId());
        }
        if (vehicle instanceof AbstractVehicleLinkedUavExt ext && !ext.ywzj_rvp$isDeployableUavInstance()) {
            RVP_LoiterConfig parentConfig = RVP_LoiterConfigCache.get(vehicle.getVehicleId());
            if (parentConfig.isConfigured()) {
                return parentConfig;
            }
        }
        return RVP_LoiterConfigCache.get(uav.getVehicleId());
    }
}
