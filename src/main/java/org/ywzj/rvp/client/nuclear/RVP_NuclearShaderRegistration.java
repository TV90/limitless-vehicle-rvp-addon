package org.ywzj.rvp.client.nuclear;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

import java.io.IOException;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_NuclearShaderRegistration {

    private RVP_NuclearShaderRegistration() {}

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        RVP_NuclearShockwaveRenderer.registerShaders(event);
    }
}
