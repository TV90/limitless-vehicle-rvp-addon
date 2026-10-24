package org.ywzj.rvp.client.nuclear;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveRenderer;

import java.io.IOException;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_NuclearShaderRegistration {

    private RVP_NuclearShaderRegistration() {}

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        // 调用共享冲击波入口注册既有 warp_world 资源，核爆与新工厂共用同一着色器。
        RVP_ShockwaveRenderer.registerShaders(event);
    }
}
