package org.ywzj.rvp.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.uav.RVP_DeployableUavService;

/**
 * 无人机座位锁 / 自动上车的服务端驱动：
 * <ul>
 *   <li>每 tick 驱动自动上车延迟重试（母车实体被卸载、区块未加载时等待重试）；</li>
 *   <li>玩家重生时解锁其锁定的母车座位（防止座位被永久锁死）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_UavSeatLockEventHandler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerLevel serverLevel = server.overworld();
        if (serverLevel != null) {
            RVP_DeployableUavService.onServerTick(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            RVP_DeployableUavService.unlockSeatForPlayer(serverPlayer.level(), serverPlayer.getId());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            RVP_DeployableUavService.unlockSeatForPlayer(serverPlayer.level(), serverPlayer.getId());
        }
    }
}
