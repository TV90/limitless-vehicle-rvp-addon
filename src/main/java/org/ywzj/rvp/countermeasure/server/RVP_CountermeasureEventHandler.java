package org.ywzj.rvp.countermeasure.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 干扰物运行时驱动（服务端）：
 * <ul>
 *   <li>载具加入世界 → 从 {@link RVP_CountermeasureStateSavedData} 恢复状态；</li>
 *   <li>每 tick（服务端）→ 对所有载具推进 {@link RVP_CountermeasureRuntimeManager#tick}
 *       （状态机发射 / 雷达箔条判定）+ 清理禁锁过期条目；</li>
 *   <li>载具离开世界 → 状态写回独立存档并清理内存。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CountermeasureEventHandler {

    private RVP_CountermeasureEventHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof AbstractVehicle vehicle
                && event.getLevel() instanceof ServerLevel serverLevel) {
            RVP_CountermeasureRuntimeManager.onVehicleJoin(vehicle, serverLevel);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof AbstractVehicle vehicle
                && event.getLevel() instanceof ServerLevel serverLevel) {
            RVP_CountermeasureRuntimeManager.onVehicleLeave(vehicle, serverLevel);
        }
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
        long gameTime = 0;
        for (ServerLevel level : server.getAllLevels()) {
            gameTime = level.getGameTime();
            for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle vehicle) {
                    RVP_CountermeasureRuntimeManager.tick(vehicle);
                }
            }
        }
        RVP_ChaffJamState.onServerTick(gameTime);
    }
}
