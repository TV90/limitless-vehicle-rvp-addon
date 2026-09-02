package org.ywzj.rvp.client.compat.distanthorizons;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import org.ywzj.rvp.RVP_MOD;

import java.io.IOException;

/** 在客户端资源阶段注册 DH 兼容 shader。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_DhShaderRegistration {
    private RVP_DhShaderRegistration() {
    }

    /** 调用本项目合成器注册深度感知与晚期降级 shader。 */
    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        if (!ModList.get().isLoaded("distanthorizons")) {
            return;
        }
        // 调用本项目合成器，仅在 DH 实际安装的客户端加载兼容 shader。
        RVP_DhDepthCompositeRenderer.registerShaders(event);
    }
}
