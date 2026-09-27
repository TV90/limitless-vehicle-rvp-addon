package org.ywzj.rvp.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CBoneModuleState;
import org.ywzj.rvp.radar.RVP_RadarModuleEnforcer;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.vehicle.RVP_EraStateSavedData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Map;
import java.util.Set;

/**
 * 骨骼模块状态侧表与独立存档的桥接：
 * <ul>
 *   <li>载具加入世界 → 从 {@link RVP_EraStateSavedData} 恢复失效模块集合进内存侧表，
 *       恢复出失效雷达骨时下一 tick 强制压回关闭（堵"重启后 RadarUnit.on 默认开机"窗口）；</li>
 *   <li>载具离开世界 → 把内存侧表写回存档并清理内存条目；</li>
 *   <li>服务器停止 → 内存侧表全量 flush 兜底落盘（leave 未触发的异常路径）；</li>
 *   <li>玩家开始追踪载具 → 推送当前失效模块状态（重连/中途进入时渲染状态不丢失）。</li>
 * </ul>
 *
 * <p>维度统一（2026-09-28 修复）：读写一律固定<b>主世界</b>的 SavedData 实例——与
 * {@code RVP_BoneModuleStateTable.persist()} 写入端一致（原实现 persist 写主世界、
 * join/leave 读写载具所在维度实例，非主世界维度打坏的模块在 leave 未落盘时重启即丢）。
 * {@code RVP_EraStateSavedData} 类注释声明即为"挂在主世界 SavedData 上"。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_EraStateEventHandler {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 维度统一：读主世界实例（与 persist 写入端同源）
        Map<String, Set<BoneModuleType>> saved =
                RVP_EraStateSavedData.get(serverLevel.getServer().overworld()).readEntry(vehicle.getUUID());
        if (saved != null && !saved.isEmpty()) {
            RVP_BoneModuleStateTable.setInactiveModules(vehicle.getUUID(), saved);
            // [RVP] 恢复出失效雷达骨：下一 tick 强制压回关闭——本体 RadarUnit.on 不持久化，
            // 重启后默认开机，原依赖 20t 巡检（恢复时序缝隙内可被玩家抢开）；server.execute
            // 在 initData（PartUnit 建立）之后执行，enforceVehicle 才能遍历到雷达部件
            serverLevel.getServer().execute(() -> {
                if (!vehicle.isRemoved()) {
                    RVP_RadarModuleEnforcer.enforceVehicle(vehicle);
                }
            });
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 维度统一：写主世界实例（与 persist 写入端同源）
        Map<String, Set<BoneModuleType>> modules =
                RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID());
        if (!modules.isEmpty()) {
            RVP_EraStateSavedData.get(serverLevel.getServer().overworld()).writeEntry(vehicle.getUUID(), modules);
        }
        RVP_BoneModuleStateTable.onVehicleLeave(vehicle.getUUID());
    }

    /**
     * [RVP] 停服兜底落盘（2026-09-28）：服务器停止前把内存侧表全部条目 flush 进主世界
     * SavedData——覆盖 {@code EntityLeaveLevelEvent} 未按预期触发的路径（强杀进程前的
     * 关卡关闭、实体批量卸载时序等），幂等（leave 已写过的原样覆盖）。
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        int flushed = RVP_BoneModuleStateTable.flushAll(event.getServer().overworld());
        if (flushed > 0) {
            RVP_EraStateEventHandler.LOGGER.info("[RVP-BoneModuleState] 停服兜底落盘 {} 条失效记录", flushed);
        }
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * 玩家开始追踪载具实体（进入视野/重连后载具重新加载）时，推送当前失效模块状态，
     * 避免客户端侧表为空导致已击毁的爆反/传感器重新渲染为生效状态。
     */
    @SubscribeEvent
    public static void onPlayerStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (!(target instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Map<String, Set<BoneModuleType>> inactive =
                RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID());
        if (inactive.isEmpty()) {
            return;
        }
        RVP_Network.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                S2CBoneModuleState.create(vehicle, inactive)
        );
    }
}
