package org.ywzj.rvp.client.debug;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.debug.RVP_HitboxDebug;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HitboxDebugClientTicker {
    private RVP_HitboxDebugClientTicker() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !RVP_HitboxDebug.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.tickCount % RVP_HitboxDebug.getIntervalTicks() != 0) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.getVehicle();
        RVP_HitboxDebug.dumpVehicleSnapshot("periodic", vehicle);
    }
}
