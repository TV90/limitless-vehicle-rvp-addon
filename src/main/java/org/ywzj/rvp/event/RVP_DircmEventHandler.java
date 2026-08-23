package org.ywzj.rvp.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.dircm.RVP_DircmRuntimeManager;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * DIRCM（定向红外对抗）运行时驱动（纯 Forge 事件，无 mixin，双端安全）：
 * <ul>
 *   <li>载具加入世界 → 从 {@link org.ywzj.rvp.vehicle.RVP_DircmStateSavedData} 恢复状态；</li>
 *   <li>每 tick（服务端）→ 对所有载具推进 {@link RVP_DircmRuntimeManager#tick}
 *       （扫描/照射/充能/HUD 同步），并对 HITL 弹推进临时干扰恢复；</li>
 *   <li>载具离开世界 → 状态写回独立存档并清理内存。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_DircmEventHandler {

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
        RVP_DircmRuntimeManager.onVehicleJoin(vehicle, serverLevel);
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
        RVP_DircmRuntimeManager.onVehicleLeave(vehicle, serverLevel);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof AbstractVehicle vehicle)) {
                    continue;
                }
                if (vehicle.isRemoved() || vehicle.isDestroyed()) {
                    continue;
                }
                RVP_DircmRuntimeManager.tick(vehicle);
            }
            // HITL 弹临时干扰恢复（服务端）
            for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
                if (entity instanceof RVP_BaseBullet bullet && bullet.dircmJammed && bullet.dircmHitlTemporary) {
                    RVP_DircmRuntimeManager.tickHitlJamRecovery(bullet);
                }
            }
        }
    }
}