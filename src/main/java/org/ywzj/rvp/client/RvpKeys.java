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
import org.ywzj.rvp.YwzjRvp;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YwzjRvp.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RvpKeys {

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

    public static final KeyMapping ANTI_RADIATION_SELECT_PREV = new KeyMapping(
            "key.ywzj_rvp.anti_radiation_select_prev.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_BRACKET,
            "key.category.ywzj_rvp"
    );

    public static final KeyMapping ANTI_RADIATION_SELECT_NEXT = new KeyMapping(
            "key.ywzj_rvp.anti_radiation_select_next.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_CAPS_LOCK,
            "key.category.ywzj_rvp"
    );

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(SET_GPS_TARGET);
        event.register(OPEN_GPS_PANEL);
        event.register(CLEAR_GPS);
        event.register(ANTI_RADIATION_SELECT_PREV);
        event.register(ANTI_RADIATION_SELECT_NEXT);
    }
}
