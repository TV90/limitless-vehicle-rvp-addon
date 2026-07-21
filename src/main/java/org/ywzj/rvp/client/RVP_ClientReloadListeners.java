package org.ywzj.rvp.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.resource.RVP_CustomMountReloadListener;
import org.ywzj.rvp.client.resource.RVP_DisplayTransparentModeManager;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_ClientReloadListeners {

    private RVP_ClientReloadListeners() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(RVP_DisplayTransparentModeManager.INSTANCE);
        event.registerReloadListener(RVP_CustomMountReloadListener.INSTANCE);
    }
}
