package org.ywzj.rvp.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.vehicle.RVP_EraStateSavedData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Map;
import java.util.Set;

/**
 * 骨骼模块状态侧表与独立存档的桥接：
 * <ul>
 *   <li>载具加入世界 → 从 {@link RVP_EraStateSavedData} 恢复失效模块集合进内存侧表；</li>
 *   <li>载具离开世界 → 把内存侧表写回存档并清理内存条目；</li>
 *   <li>玩家开始追踪载具 → 推送当前失效模块状态（重连/中途进入时渲染状态不丢失）。</li>
 * </ul>
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
        Map<String, Set<BoneModuleType>> saved =
                RVP_EraStateSavedData.get(serverLevel).readEntry(vehicle.getUUID());
        if (saved != null && !saved.isEmpty()) {
            RVP_BoneModuleStateTable.setInactiveModules(vehicle.getUUID(), saved);
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
        Map<String, Set<BoneModuleType>> modules =
                RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID());
        if (!modules.isEmpty()) {
            RVP_EraStateSavedData.get(serverLevel).writeEntry(vehicle.getUUID(), modules);
        }
        RVP_BoneModuleStateTable.onVehicleLeave(vehicle.getUUID());
    }

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
