package org.ywzj.rvp.client.handler;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.gui.RVP_ArmOverlay;
import org.ywzj.rvp.client.state.RVP_ClientArmState;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_ClientArmTickHandler {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        RVP_ClientArmState.getInstance().tick();

        // Handle key presses outside GUI
        RVP_ClientArmState state = RVP_ClientArmState.getInstance();
        if (!state.isActive()) {
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
        RVP_ArmOverlay.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        RVP_ClientArmState state = RVP_ClientArmState.getInstance();
        if (!state.isActive()) {
            return;
        }
        while (RVP_Keys.ARM_SELECT_PREV.consumeClick()) {
            state.selectPrev();
        }
        while (RVP_Keys.ARM_SELECT_NEXT.consumeClick()) {
            state.selectNext();
        }
    }
}
