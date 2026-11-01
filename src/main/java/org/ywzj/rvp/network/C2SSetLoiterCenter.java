package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.rvp.uav.RVP_LinkedUavStateTable;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

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
            if (!Double.isFinite(msg.x) || !Double.isFinite(msg.y) || !Double.isFinite(msg.z)
                    || Math.abs(msg.x) > 30_000_000.0 || Math.abs(msg.z) > 30_000_000.0
                    || msg.y < -2048.0 || msg.y > 4096.0) {
                return;
            }
            if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.not_in_vehicle"), true);
                return;
            }
            // 解析目标无人机：当前驾驶的是无人机则返回它，否则查关联的子无人机
            AbstractVehicle uav = resolveTargetUav(vehicle);
            if (uav == null || !RVP_DeployableUavService.isLoiterControlOperator(player, vehicle, uav)) {
                return;
            }
            // 信号范围守卫（2026-10-06）：失联的无人机不可标记圆心
            if (RVP_DeployableUavService.isUavOutOfSignalRange(uav)) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav.signal_lost"), true);
                return;
            }

            // 获取盘旋配置
            RVP_LoiterConfig config = resolveLoiterConfig(vehicle, uav);
            if (!config.isConfigured()) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.no_config"), true);
                return;
            }

            Vec3 center = new Vec3(msg.x, msg.y, msg.z);
            // 目标高度 = 圆心 Y + 配置偏移（resolveTargetAltitude 统一计算）；圆心 Y 取标记点 Y，
            // 高度语义不再混入"标记时的当前高度"（2026-10-06 参数精简）
            UUID uavUuid = uav.getUUID();

            if (RVP_UavLoiterManager.isLoitering(uavUuid)) {
                // 已在盘旋：更新圆心
                RVP_UavLoiterManager.updateCenter(uavUuid, center, center.y);
            } else {
                // 未盘旋：以标记点为固定圆心激活
                RVP_UavLoiterManager.enableMarkedCenter(uavUuid, center, config.loiterRadius(), center.y);
            }
            RVP_UavLoiterManager.persist(uav);
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.center_set"), true);
        });
        ctx.setPacketHandled(true);
    }

    private static AbstractVehicle resolveTargetUav(AbstractVehicle vehicle) {
        if (isLoiterCapable(vehicle) && RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) {
            return vehicle;
        }
        // 只有本身为飞行载具时才优先使用自身配置；运输车/母车上的 loiter 参数不接管子机。
        RVP_LoiterConfig selfConfig = RVP_LoiterConfigCache.get(vehicle.getVehicleId());
        if (isLoiterCapable(vehicle) && selfConfig.isConfigured()) {
            return vehicle;
        }
        Optional<AbstractVehicle> child = RVP_DeployableUavService.getLinkedChild(vehicle);
        AbstractVehicle linked = child.orElse(null);
        return linked != null && isLoiterCapable(linked) ? linked : null;
    }

    private static boolean isLoiterCapable(AbstractVehicle vehicle) {
        return vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle;
    }

    private static RVP_LoiterConfig resolveLoiterConfig(AbstractVehicle vehicle, AbstractVehicle uav) {
        if (vehicle == uav) {
            if (RVP_LinkedUavStateTable.isDeployableUavInstance(uav)) {
                AbstractVehicle parent = RVP_DeployableUavService.getLinkedParent(uav).orElse(null);
                if (parent != null) {
                    RVP_LoiterConfig parentConfig = RVP_LoiterConfigCache.get(parent.getVehicleId());
                    if (parentConfig.isConfigured()) return parentConfig;
                }
            }
            return RVP_LoiterConfigCache.get(uav.getVehicleId());
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) {
            RVP_LoiterConfig parentConfig = RVP_LoiterConfigCache.get(vehicle.getVehicleId());
            if (parentConfig.isConfigured()) {
                return parentConfig;
            }
        }
        return RVP_LoiterConfigCache.get(uav.getVehicleId());
    }
}
