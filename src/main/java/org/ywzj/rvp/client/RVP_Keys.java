package org.ywzj.rvp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.RVP_MOD;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_Keys {

    public static final KeyMapping SET_GPS_TARGET = new KeyMapping(
            "key.ywzj_rvp.set_gps_target.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            "key.category.ywzj_rvp"
    );

    public static final KeyMapping OPEN_GPS_PANEL = new KeyMapping(
            "key.ywzj_rvp.open_gps_panel.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.category.ywzj_rvp"
    );

    public static final KeyMapping CLEAR_GPS = new KeyMapping(
            "key.ywzj_rvp.clear_gps.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
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

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(SET_GPS_TARGET);
        event.register(OPEN_GPS_PANEL);
        event.register(CLEAR_GPS);
        event.register(WEAPON_TEST_OVERLAY);
    }
}
