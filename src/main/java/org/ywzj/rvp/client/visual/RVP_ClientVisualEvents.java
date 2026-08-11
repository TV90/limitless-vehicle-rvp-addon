package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

/** 通用客户端视觉效果的 Tick、渲染和世界清理事件入口。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientVisualEvents {
    private RVP_ClientVisualEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // 调用 RVP 客户端视觉分派器，清除退出世界后遗留的实例。
            RVP_ClientVisualEffectDispatcher.clear();
            return;
        }
        // 调用 RVP 客户端视觉分派器，统一推进当前世界的效果实例。
        RVP_ClientVisualEffectDispatcher.tick(level);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            // 调用 RVP 客户端视觉分派器，在天气之后统一渲染半透明世界效果。
            RVP_ClientVisualEffectDispatcher.render(event);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            // 调用 RVP 客户端视觉分派器，确保切换维度时实例不会跨世界复用。
            RVP_ClientVisualEffectDispatcher.clear();
        }
    }
}
