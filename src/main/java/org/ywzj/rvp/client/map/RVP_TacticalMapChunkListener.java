package org.ywzj.rvp.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_TacticalMapChunkListener {

    private RVP_TacticalMapChunkListener() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel)) {
            return;
        }
        if (event.getChunk() instanceof LevelChunk chunk) {
            RVP_TacticalMapCache.queueChunkUpdate(chunk);
        }
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel clientLevel) {
            RVP_TacticalMapCache.setWorldContext(clientLevel);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            RVP_TacticalMapCache.clear();
        }
    }
}
