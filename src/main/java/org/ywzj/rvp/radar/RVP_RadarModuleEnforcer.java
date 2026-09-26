package org.ywzj.rvp.radar;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

/**
 * [RVP] 雷达骨骼部件（RADAR bone module）强制关闭器（服务端，2026-09-26 新增）。
 *
 * <p>语义：{@code bone_modules} 雷达骨（骨名 = 雷达 PartUnit id，如 cssa5/ps1sm 的
 * {@code lock_radar}/{@code scan_radar}）上的 RADAR 模块被击毁后，对应 {@link RadarUnit}
 * 被服务端强制保持<b>关闭</b>（{@code toggle(false)}，清锁定目标）。多雷达载具按雷达骨
 * 分粒度——跟踪雷达被打坏只关跟踪雷达，搜索雷达被打坏只关搜索雷达。</p>
 *
 * <p>为什么需要"强制保持"：雷达 {@code on} 状态存在三个会把它重新打开的入口——
 * ① gunner 锁定前自动开机（{@code RVP_GunnerRadarActions.prepareLockRadar}）、
 * ② 中继车 gunner 每 tick 保活开机（{@code GunnerExternalRadarController.turnOnRelayRadars}）、
 * ③ 玩家手动开机同步包（{@code C2SRadarPowerToggle}）。这三处已各自加失效 gate（双保险），
 * 本类的低频巡检（每 20 tick）再兜底压回，任何遗漏路径下一秒内都会被纠正。</p>
 *
 * <p>联动（零改动自然成立）：外置雷达中继链 {@code RVP_ExternalRadarSyncService.hasAnyRadarOn}
 * 逐雷达查 {@code isOn()}——中继车雷达被强制关闭后母车探测/扇区/锁自动清空；
 * HUD/RWR/扫描链路全部自带 {@code isOn()} 门自然停摆；开关动画自动播放关闭帧。</p>
 *
 * <p>维修恢复：{@code RVP_MaintenanceRuntimeManager} 恢复含 RADAR 的骨后调用
 * {@link #restoreRadar}（修好自动开机，用户 2026-09-26 定版）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RadarModuleEnforcer {

    /** 巡检间隔（tick）：雷达重复开机的最大残留窗口，1 秒内纠正。 */
    private static final int ENFORCE_INTERVAL_TICK = 20;

    private RVP_RadarModuleEnforcer() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        if (server.getTickCount() % ENFORCE_INTERVAL_TICK != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle vehicle && !vehicle.isDestroyed()) {
                    enforceVehicle(vehicle);
                }
            }
        }
    }

    /**
     * 单载具巡检：全部雷达部件中，RADAR 模块已失效且仍处于开机的，强制关闭。
     * 未配置雷达部件的载具 {@code isModuleActive} 恒真，零行为、只有部件级遍历开销。
     */
    public static void enforceVehicle(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.level().isClientSide()) {
            return;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit
                    && radarUnit.isOn()
                    && !RVP_BoneModuleStateTable.isModuleActive(
                            vehicle.getUUID(), radarUnit.getId(), BoneModuleType.RADAR)) {
                radarUnit.toggle(false);
            }
        }
    }

    /**
     * 模块失效即时关闭（{@code tryDestroyBoneModules} 销毁 RADAR 后调用）：
     * {@code toggle(false)} 会同时清掉该雷达的锁定目标（区别于 SwitchableUnit.setOn）。
     */
    public static void forceRadarOff(AbstractVehicle vehicle, @Nullable String boneName) {
        if (vehicle == null || boneName == null || vehicle.level().isClientSide()) {
            return;
        }
        if (vehicle.getPartUnit(boneName).orElse(null) instanceof RadarUnit radarUnit && radarUnit.isOn()) {
            radarUnit.toggle(false);
        }
    }

    /**
     * 维修恢复后的自动开机（用户 2026-09-26 定版）：RADAR 模块恢复存活且雷达处于
     * 关闭时 {@code toggle(true)}。由 {@code RVP_MaintenanceRuntimeManager} 恢复链调用。
     */
    public static void restoreRadar(AbstractVehicle vehicle, @Nullable String boneName) {
        if (vehicle == null || boneName == null || vehicle.level().isClientSide()) {
            return;
        }
        if (vehicle.getPartUnit(boneName).orElse(null) instanceof RadarUnit radarUnit
                && !radarUnit.isOn()
                && RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), boneName, BoneModuleType.RADAR)) {
            radarUnit.toggle(true);
        }
    }
}
