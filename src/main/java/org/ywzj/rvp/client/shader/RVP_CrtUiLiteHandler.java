package org.ywzj.rvp.client.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.client.render.util.PostPassesGetter;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_CrtUiLiteHandler implements ResourceManagerReloadListener {
    private static final ResourceLocation CRT_UI_LITE_EFFECT =
            RVP_MOD.resourceLocation("ywzj_rvp:shaders/post/crt_ui_lite.json");

    private static PostChain postChain;
    private static int lastWidth;
    private static int lastHeight;
    private static boolean active;

    public static void setActive(boolean active) {
        if (RVP_CrtUiLiteHandler.active != active) {
            RVP_CrtUiLiteHandler.active = active;
            if (!active) {
                cleanup();
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        cleanup();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureChain(minecraft)) {
            return;
        }
        postChain.process(event.getPartialTick());
        if (postChain instanceof PostPassesGetter getter) {
            for (PostPass pass : getter.getPasses()) {
                pass.getEffect().safeGetUniform("Resolution")
                        .set((float) minecraft.getWindow().getWidth(), (float) minecraft.getWindow().getHeight());
            }
        }
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    private static boolean ensureChain(Minecraft mc) {
        if (postChain == null) {
            try {
                postChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                        mc.getMainRenderTarget(), CRT_UI_LITE_EFFECT);
                postChain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
                lastWidth = mc.getWindow().getWidth();
                lastHeight = mc.getWindow().getHeight();
            } catch (Exception e) {
                e.printStackTrace();
                active = false;
                return false;
            }
        }
        if (lastWidth != mc.getWindow().getWidth() || lastHeight != mc.getWindow().getHeight()) {
            lastWidth = mc.getWindow().getWidth();
            lastHeight = mc.getWindow().getHeight();
            postChain.resize(lastWidth, lastHeight);
        }
        return true;
    }

    private static void cleanup() {
        if (postChain != null) {
            postChain.close();
            postChain = null;
        }
    }
}
