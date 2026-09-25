package org.ywzj.rvp.maintenance.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * 快速维修运行时驱动（服务端），结构照 {@code RVP_CountermeasureEventHandler}：
 * <ul>
 *   <li>载具加入世界 → 从实体 NBT 恢复冷却 / 生效剩余；</li>
 *   <li>每 tick（服务端）→ 对所有载具推进 {@link RVP_MaintenanceRuntimeManager#tick}
 *       （冷却递减 / 生效期回血 / HUD 节流推送）；</li>
 *   <li>载具离开世界 → 状态写回实体 NBT 并清理内存。</li>
 * </ul>
 * 未配置 maintenance 的载具在 tick 内一次查表即返回，零额外开销。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_MaintenanceEventHandler {

    private RVP_MaintenanceEventHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            RVP_MaintenanceRuntimeManager.onVehicleJoin(vehicle);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            RVP_MaintenanceRuntimeManager.onVehicleLeave(vehicle);
            // [RVP] 维修顺序为无持久化的会话内状态，随载具离开世界一并清理
            RVP_RepairOrderTable.onVehicleLeave(vehicle.getUUID());
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
        for (ServerLevel level : server.getAllLevels()) {
            // 先快照载具列表再 tick：heal 不抛实体，但保持与干扰物驱动同款防御
            // （level.getEntities().getAll() 返回活列表，遍历中加实体会导致遍历不终止）
            List<AbstractVehicle> vehicles = new ArrayList<>();
            for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle vehicle) {
                    vehicles.add(vehicle);
                }
            }
            for (AbstractVehicle vehicle : vehicles) {
                RVP_MaintenanceRuntimeManager.tick(vehicle);
            }
        }
    }
}
