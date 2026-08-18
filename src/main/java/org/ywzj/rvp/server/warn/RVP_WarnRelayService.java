package org.ywzj.rvp.server.warn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CRvpWarn;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

/**
 * 服务端雷达告警无钳制补发（替代本体通道的 ±45° 俯仰角钳制）。
 *
 * <p>本体 {@code RadarUnit.tickScan}/{@code WeaponUnit.tick} 向目标发送 RADAR_SEARCH /
 * RADAR_LOCK 走本体 {@code Channel}，客户端 {@code WarningReceiver.handle} 对俯仰角超
 * ±45° 的告警一律丢弃。本服务自建 RVP 告警包 {@link S2CRvpWarn}，低频（每 5 tick，
 * 覆盖本体 500ms 告警窗口）遍历所有载具的雷达：命中雷达锁定或探测表中有载具目标时，
 * 直接向该目标载具的乘客玩家补发无钳制告警，客户端写 targets 后 RWR 不受角度钳制。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WarnRelayService {

    /** 补发扫描间隔（tick）：每 5 tick 一次，小于本体 WarningReceiver 500ms 清窗周期。 */
    private static final int SCAN_INTERVAL_TICK = 5;

    private RVP_WarnRelayService() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % SCAN_INTERVAL_TICK != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level);
        }
    }

    private static void scanLevel(ServerLevel level) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle source) || source.isDestroyed() || !source.isAlive()) {
                continue;
            }
            for (PartUnit<?> partUnit : source.getPartUnits()) {
                if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                    continue;
                }
                relayRadarLock(source, radar);
                relayRadarSearch(source, radar);
            }
        }
    }

    /** 补发雷达锁定（RADAR_LOCK）：雷达已锁定目标载具时向目标乘客发无钳制告警。 */
    private static void relayRadarLock(AbstractVehicle source, RadarUnit radar) {
        Entity locked = radar.getLockedEntity();
        if (!(locked instanceof AbstractVehicle target) || !target.isAlive()) {
            return;
        }
        broadcastWarn(source, target,
                new S2CRvpWarn(source.getId(), target.getId(), WarnType.RADAR_LOCK, radar.getRadarType()));
    }

    /** 补发雷达搜索（RADAR_SEARCH）：雷达探测表含载具目标时向目标乘客发无钳制告警。 */
    private static void relayRadarSearch(AbstractVehicle source, RadarUnit radar) {
        for (RadarUnit.DetectedObject detected : radar.getDetectedEntities().values()) {
            if (detected.entity instanceof AbstractVehicle target && target.isAlive()) {
                broadcastWarn(source, target,
                        new S2CRvpWarn(source.getId(), target.getId(), WarnType.RADAR_SEARCH, radar.getRadarType()));
            }
        }
    }

    /** 向目标载具的所有乘客玩家发送 RVP 无钳制告警包。 */
    private static void broadcastWarn(AbstractVehicle source, AbstractVehicle target, S2CRvpWarn packet) {
        if (target == null || !target.isAlive()) {
            return;
        }
        for (Entity passenger : target.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }
}