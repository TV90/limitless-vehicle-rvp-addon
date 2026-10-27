package org.ywzj.rvp.weapon.effects;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

/**
 * 服务端驱动 {@link RVP_VehicleExplosion} 的延迟破坏队列。
 *
 * <p>本体在原 {@code VehicleExplosion} 上由 {@code AllEvents.onServerTick} 每 tick 调
 * {@code VehicleExplosion.tick(level)} 推进大半径爆炸的分片破坏；爆炸逻辑抄到 RVP 自维护后
 * 这条驱动必须由 RVP 自己提供，否则核爆炸（半径 &gt; 32 且未强制即时）的方块破坏永远不会推进。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ExplosionTickHandler {

    private RVP_ExplosionTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            RVP_VehicleExplosion.tick(level);
        }
    }
}
