package org.ywzj.rvp.event;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * physics-only 碰撞体积的驱动：
 * <ul>
 *   <li>载具加入世界 → 重建 physics-only 盒（替代被删 mixin 的 {@code initData} 注入）；</li>
 *   <li>每 tick（服务端/客户端各一）→ 更新盒的世界坐标（替代 {@code updateOBBs} 注入）；</li>
 *   <li>载具离开世界 → 清理侧表条目。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_PhysicsOnlyCollisionEventHandler {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        RVP_PhysicsOnlyCollisionHelper.rebuildPhysicsOnlyCubes(vehicle);
    }

    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            RVP_PhysicsOnlyCollisionHelper.onVehicleLeave(vehicle);
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
            RVP_PhysicsOnlyCollisionHelper.tickVehicles(level);
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            RVP_PhysicsOnlyCollisionHelper.tickClientVehicles(level);
        }
    }
}
