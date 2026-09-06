package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
            // [RVP] 不在载具：不适用场景，静默（客户端 F 键守卫已放行在载具的情况，此处仅兜底），
            // 不回提示避免覆盖其它 mod 的 actionbar 反馈
            if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
                return;
            }

            AbstractVehicle uav = resolveTargetUav(vehicle);
            if (uav == null) {
                return;
            }

            UUID uavUuid = uav.getUUID();
            if (RVP_UavLoiterManager.isLoitering(uavUuid)) {
                RVP_UavLoiterManager.disable(uavUuid);
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.disabled"), true);
            } else {
                RVP_LoiterConfig config = resolveLoiterConfig(vehicle, uav);
                // [RVP] 无盘旋配置：不适用场景，静默（目标载具是否支持盘旋已由
                // resolveTargetUav 的判型 + 客户端 F 键守卫双重过滤，此处仅兜底）
                if (!config.isConfigured()) {
                    return;
                }
                // 确定圆心：有母车则跟随母车，否则用当前位置
                // 高度维持无人机当前高度，避免自动爬升
                double loiterAlt = uav.getY();
                AbstractVehicle parent = resolveParent(vehicle, uav);
                if (parent != null) {
                    RVP_UavLoiterManager.enableFollowParent(
                            uavUuid, parent.getUUID(),
                            config.loiterRadius(), loiterAlt,
                            parent.getX(), parent.getY(), parent.getZ()
                    );
                } else {
                    // 无母车：以当前位置为固定圆心（AC130 等场景）
                    RVP_UavLoiterManager.enableMarkedCenter(
                            uavUuid, uav.position(),
                            config.loiterRadius(), loiterAlt
                    );
                }
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.uav_loiter.enabled"), true);
            }
        });
        ctx.setPacketHandled(true);
    }

    /** 解析目标盘旋载具：无人机实例 → 自身有盘旋配置的载具 → 关联子无人机。 */
    private static AbstractVehicle resolveTargetUav(AbstractVehicle vehicle) {
        // [RVP] 各分支统一判型：仅固定翼/旋翼可盘旋。地面子机（如 Buk-M3 / IRIS-T 的
        // 96l6、irist_slm_tads 轮式雷达车）无盘旋语义，静默返回 null，不再误报"未配置盘旋参数"。
        if (RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle) && isLoiterCapable(vehicle)) {
            return vehicle;
        }
        // AC130 等自身带盘旋配置的固定翼载具，直接对自身盘旋
        if (isLoiterCapable(vehicle)) {
            RVP_LoiterConfig selfConfig = RVP_LoiterConfigCache.get(vehicle.getVehicleId());
            if (selfConfig.isConfigured()) {
                return vehicle;
            }
        }
        Optional<AbstractVehicle> child = RVP_DeployableUavService.getLinkedChild(vehicle);
        return child.filter(C2SToggleUavLoiter::isLoiterCapable).orElse(null);
    }

    /**
     * [RVP] 盘旋能力判型：仅固定翼与旋翼载具具备盘旋语义。
     * 本体车型恰为 fixed_wing / rotary_wing / wheeled / tracked / custom 几类，
     * 地面轮式/履带载具（含可部署的雷达 relay 子机）一律不参与盘旋。
     */
    private static boolean isLoiterCapable(AbstractVehicle vehicle) {
        return vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle;
    }

    /** 解析母车：目标就是当前载具自身时无母车，否则按原逻辑。 */
    private static AbstractVehicle resolveParent(AbstractVehicle vehicle, AbstractVehicle uav) {
        if (vehicle == uav) {
            return null;
        }
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) {
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
