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
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CRvpWarn;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 服务端雷达告警无钳制补发（替代本体通道的 ±45° 俯仰角钳制）。
 *
 * <p>本体 {@code RadarUnit.tickScan}/{@code WeaponUnit.tick} 向目标发送 RADAR_SEARCH /
 * RADAR_LOCK 走本体 {@code Channel}，客户端 {@code WarningReceiver.handle} 对俯仰角超
 * ±45° 的告警一律丢弃。本服务自建 RVP 告警包 {@link S2CRvpWarn}，低频（每 5 tick，
 * 覆盖本体 500ms 告警窗口）遍历所有载具的雷达：命中雷达锁定或探测表中有载具目标时，
 * 直接向该目标载具的乘客玩家补发无钳制告警，客户端写 targets 后 RWR 不受角度钳制。</p>
 *
 * <p><b>RADAR_SEARCH 响声调度（2026-09-06 重构）</b>：本体每 20 tick 固定响一声、与
 * 敌方雷达实际扫描周期无关（其独立扫描还无高度窗/扇区门控），已被客户端
 * {@code WarningReceiverSearchWarnMixin} 截流。本服务成为搜索告警唯一来源：
 * 目标首次进入雷达探测表 → {@code audible=true} 立即响一声；仍在表内每过一个
 * 扫描周期（phase 雷达 = {@code scan_period_tick}，非 phase 雷达 = 20 tick 对标本体
 * 每秒节奏）再响一声（= 被扫描一轮响一声）；其余轮询 {@code audible=false} 仅刷新
 * targets，保 RWR 图标/文字常亮不闪。目标离开探测表（出体积/死亡）或雷达关机即停响，
 * 重新接触视为首次接触。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WarnRelayService {

    /** 补发扫描间隔（tick）：每 5 tick 一次，小于本体 WarningReceiver 500ms 清窗周期。 */
    private static final int SCAN_INTERVAL_TICK = 5;

    /** 非 phase 雷达的搜索告警响声周期（tick）：对标本体 tickScan 每秒一响的节奏。 */
    private static final int MECHANICAL_AUDIBLE_PERIOD_TICK = 20;

    /** phase 雷达配置了 scan_period_tick 时响声周期的下限：低于 0.5 秒一响按此封底。 */
    private static final int MIN_AUDIBLE_PERIOD_TICK = 10;

    /**
     * 搜索告警响声状态：key = "载具id:雷达id:目标id" → 上次响声（audible=true）的服务端 tick。
     * 目标离开探测表/雷达关机即清理（重进视为首次接触再响一声）。
     */
    private static final Map<String, Integer> SEARCH_AUDIBLE_STATE = new HashMap<>();

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
        int serverTick = server.getTickCount();
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level, serverTick);
        }
    }

    private static void scanLevel(ServerLevel level, int serverTick) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle source) || source.isDestroyed() || !source.isAlive()) {
                continue;
            }
            for (PartUnit<?> partUnit : source.getPartUnits()) {
                if (!(partUnit instanceof RadarUnit radar)) {
                    continue;
                }
                if (!radar.isOn()) {
                    // 雷达关机：清除其响声状态，重新开机后目标视为首次接触（再响一声）
                    String statePrefix = source.getId() + ":" + radar.getId() + ":";
                    SEARCH_AUDIBLE_STATE.keySet().removeIf(key -> key.startsWith(statePrefix));
                    continue;
                }
                relayRadarLock(source, radar);
                relayRadarSearch(source, radar, serverTick);
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

    /**
     * 补发雷达搜索（RADAR_SEARCH）：雷达探测表含载具目标时向目标乘客发无钳制告警。
     * 响声（audible=true）按 {@link #effectiveAudiblePeriodTick} 调度，其余轮询仅刷新。
     */
    private static void relayRadarSearch(AbstractVehicle source, RadarUnit radar, int serverTick) {
        int audiblePeriod = effectiveAudiblePeriodTick(radar);
        String statePrefix = source.getId() + ":" + radar.getId() + ":";
        Set<String> liveKeys = new HashSet<>();
        for (RadarUnit.DetectedObject detected : radar.getDetectedEntities().values()) {
            if (!(detected.entity instanceof AbstractVehicle target) || !target.isAlive()) {
                continue;
            }
            String stateKey = statePrefix + target.getId();
            liveKeys.add(stateKey);
            Integer lastAudible = SEARCH_AUDIBLE_STATE.get(stateKey);
            // 首次进入探测表立即响一声；之后每完成一轮扫描周期（仍在表内）再响一声；
            // 其余轮询 audible=false 仅写 targets，保 RWR 图标/文字活性
            boolean audible = lastAudible == null || (serverTick - lastAudible) >= audiblePeriod;
            if (audible) {
                SEARCH_AUDIBLE_STATE.put(stateKey, serverTick);
            }
            broadcastWarn(source, target,
                    new S2CRvpWarn(source.getId(), target.getId(), WarnType.RADAR_SEARCH,
                            radar.getRadarType(), audible));
        }
        // 清理已离开探测表的目标状态（出体积/死亡不再残留；目标重进探测表视为首次接触）
        SEARCH_AUDIBLE_STATE.keySet().removeIf(
                key -> key.startsWith(statePrefix) && !liveKeys.contains(key));
    }

    /**
     * 搜索告警响声周期（tick）：phase 雷达 = {@code scan_period_tick}
     * （下限 {@link #MIN_AUDIBLE_PERIOD_TICK}，未配置按本体每秒节奏）；
     * 非 phase 雷达 = {@link #MECHANICAL_AUDIBLE_PERIOD_TICK}（对标本体 tickScan 每秒一响）。
     */
    private static int effectiveAudiblePeriodTick(RadarUnit radar) {
        RadarUnitData data = radar.getData();
        if (data instanceof RadarUnitDataExt ext
                && "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode())) {
            int configured = ext.ywzj_rvp$getScanPeriodTick();
            if (configured > 0) {
                return Math.max(configured, MIN_AUDIBLE_PERIOD_TICK);
            }
        }
        return MECHANICAL_AUDIBLE_PERIOD_TICK;
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
