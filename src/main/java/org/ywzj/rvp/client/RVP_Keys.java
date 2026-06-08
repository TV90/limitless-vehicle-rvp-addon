package org.ywzj.rvp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.ywzj.rvp.client.gui.RVP_SeekerHudOverlay;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.RVP_MOD;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_Keys {

    public static final KeyMapping OPEN_GPS_PANEL = new KeyMapping(
            "key.ywzj_rvp.open_gps_panel.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.category.ywzj_rvp"
    );

    /** Toggle RVP weapon JSON test overlay (MCH test mode style). */
    public static final KeyMapping WEAPON_TEST_OVERLAY = new KeyMapping(
            "key.ywzj_rvp.weapon_test.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F10,
            "key.category.ywzj_rvp"
    );

    /** Cycle IR/SARH seeker lock among targets nearest to screen centre. */
    public static final KeyMapping CYCLE_SEEKER_TARGET = new KeyMapping(
            "key.ywzj_rvp.cycle_seeker_target.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.category.ywzj_rvp"
    );

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("ywzj_rvp_seeker_hud", RVP_SeekerHudOverlay.INSTANCE);
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GPS_PANEL);
        event.register(WEAPON_TEST_OVERLAY);
        event.register(CYCLE_SEEKER_TARGET);
    }
}
