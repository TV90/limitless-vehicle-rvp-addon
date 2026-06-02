package org.ywzj.rvp.client.shader;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.client.debug.RvpTVMissileDebug;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YwzjRvp.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TVMissileVideoPostHandler implements ResourceManagerReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation BW_EFFECT = YwzjRvp.resourceLocation("ywzj_rvp:shaders/post/tvmissile_bw.json");

    private static boolean active = false;
    private static PostChain bwChain;
    private static int lastWidth = 0;
    private static int lastHeight = 0;
    private static String lastDebugState = "";

    public static void setActive(boolean active) {
        if (TVMissileVideoPostHandler.active != active) {
            TVMissileVideoPostHandler.active = active;
            debugState("active=" + active);
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            RvpTVMissileDebug.tryDumpOnce();
            applyPostProcess(event.getPartialTick());
        }
    }

    private static void applyPostProcess(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!ensureBwChain(mc)) {
            debugState("skip:ensure-chain-failed");
            return;
        }
        try {
            debugState("process:start");
            bwChain.process(partialTick);
            debugState("process:done");
        } catch (Exception e) {
            LOGGER.error("[RVP][TVMissile][BW] process failed", e);
            cleanup();
        }
        mc.getMainRenderTarget().bindWrite(true);
    }

    private static boolean ensureBwChain(Minecraft mc) {
        if (bwChain == null) {
            try {
                bwChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(), BW_EFFECT);
                bwChain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
                lastWidth = mc.getWindow().getWidth();
                lastHeight = mc.getWindow().getHeight();
                debugState("chain:created");
            } catch (Exception e) {
                LOGGER.error("[RVP][TVMissile][BW] chain create failed", e);
                active = false;
                return false;
            }
        }
        if (lastWidth != mc.getWindow().getWidth() || lastHeight != mc.getWindow().getHeight()) {
            lastWidth = mc.getWindow().getWidth();
            lastHeight = mc.getWindow().getHeight();
            bwChain.resize(lastWidth, lastHeight);
            debugState("chain:resized");
        }
        return true;
    }

    private static void cleanup() {
        cleanupBw();
        lastWidth = 0;
        lastHeight = 0;
        lastDebugState = "";
    }

    private static void cleanupBw() {
        if (bwChain != null) {
            debugState("cleanup:chain-close");
            bwChain.close();
            bwChain = null;
        }
    }

    private static void debugState(String state) {
        Minecraft mc = Minecraft.getInstance();
        boolean spam = RvpTVMissileDebug.shouldSpamBw(mc);
        if (!spam && state.equals(lastDebugState)) {
            return;
        }
        lastDebugState = state;
        LOGGER.info("[RVP][TVMissile][BW] {}", state);
    }

}
