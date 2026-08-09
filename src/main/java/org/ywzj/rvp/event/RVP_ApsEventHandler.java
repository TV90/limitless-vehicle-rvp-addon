package org.ywzj.rvp.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.RVP_ApsRuntimeManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * APS（主动防护系统）运行时驱动（替代被删 {@code AbstractVehicleApsMixin} 的 tick 注入）：
 * <ul>
 *   <li>载具加入世界 → 从 {@link org.ywzj.rvp.vehicle.RVP_ApsStateSavedData} 恢复状态；</li>
 *   <li>每 tick（服务端）→ 对所有载具推进 {@link RVP_ApsRuntimeManager#tick}（扫描/拦截/装填/HUD 同步）；</li>
 *   <li>载具离开世界 → 状态写回独立存档并清理内存。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_ApsEventHandler {

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
        RVP_ApsRuntimeManager.onVehicleJoin(vehicle, serverLevel);
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
        RVP_ApsRuntimeManager.onVehicleLeave(vehicle, serverLevel);
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
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle vehicle) {
                    RVP_ApsRuntimeManager.tick(vehicle);
                }
            }
        }
    }
}
