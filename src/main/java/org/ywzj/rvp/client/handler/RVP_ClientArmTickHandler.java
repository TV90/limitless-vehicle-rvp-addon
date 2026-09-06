package org.ywzj.rvp.client.handler;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.gui.RVP_ArmOverlay;
import org.ywzj.rvp.client.state.RVP_ClientArmState;
import org.ywzj.rvp.client.state.RVP_ClientRadarLockState;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_ClientArmTickHandler {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        RVP_ClientArmState.getInstance().tick();
        RVP_ClientRadarLockState.getInstance().tick();

        // Handle key presses outside GUI
        RVP_ClientArmState state = RVP_ClientArmState.getInstance();
        if (!state.isActive()) {
            RVP_ClientRadarLockState radarState = RVP_ClientRadarLockState.getInstance();
            if (!radarState.isActive()) {
                return;
            }
            while (RVP_Keys.ARM_SELECT_PREV.consumeClick()) {
                radarState.selectPrev();
            }
            while (RVP_Keys.ARM_SELECT_NEXT.consumeClick()) {
                radarState.selectNext();
            }
            return;
        }
        while (RVP_Keys.ARM_SELECT_PREV.consumeClick()) {
            state.selectPrev();
        }
        while (RVP_Keys.ARM_SELECT_NEXT.consumeClick()) {
            state.selectNext();
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        // RenderGuiOverlayEvent.Post 每帧对每个已注册 overlay 各触发一次（40+ 次），
        // 只锚定每帧必渲染的原生 CHAT_PANEL 层执行一次，其余事件忽略（修复多弹/多实体时帧率腰斩）
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        RVP_ArmOverlay.render(event.getGuiGraphics());
    }

    // [RVP] 原 onKeyInput（InputEvent.Key 中重复消费 ARM_SELECT_PREV/NEXT）已删除：
    // 与 onClientTick 的消费逻辑完全重复，consumeClick 计数两处会互相抢清，
    // 单一消费点在 onClientTick。
}
