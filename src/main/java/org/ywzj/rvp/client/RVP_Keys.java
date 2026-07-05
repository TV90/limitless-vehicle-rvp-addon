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

    private static KeyMapping key(String name, InputConstants.Type type, int key) {
        return new KeyMapping(
                "key.ywzj_rvp." + name + ".desc",
                KeyConflictContext.IN_GAME,
                KeyModifier.NONE,
                type,
                key,
                "key.category.ywzj_rvp"
        );
    }

    public static final KeyMapping OPEN_GPS_PANEL = key("open_gps_panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K);

    /** Toggle RVP weapon JSON test overlay (MCH test mode style). */
    public static final KeyMapping WEAPON_TEST_OVERLAY = key("weapon_test", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10);

    /** ARM target selection (previous). */
    public static final KeyMapping ARM_SELECT_PREV = key("arm_select_prev", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET);

    /** ARM target selection (next). */
    public static final KeyMapping ARM_SELECT_NEXT = key("arm_select_next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET);

    /** GPS target set (R key while in GPS weapon seat). */
    public static final KeyMapping SET_GPS_TARGET = key("set_gps_target", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R);

    /** Clear GPS target. */
    public static final KeyMapping CLEAR_GPS = key("clear_gps", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L);
    public static final KeyMapping DEPLOY_DEPLOYABLE_UAV = key("deploy_deployable_uav", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N);
    public static final KeyMapping SWITCH_DEPLOYABLE_UAV = key("switch_deployable_uav", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M);

    /** HMD helmet-mounted display / ACM dogfight mode toggle (5 key). */
    public static final KeyMapping HMD_TOGGLE = key("hmd_toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_5);
    public static final KeyMapping FIRE_CONTROL_STABILIZER = key("fire_control_stabilizer", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F);
    public static final KeyMapping HITL_REDESIGNATE = key("hitl_redesignate", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R);
    public static final KeyMapping HITL_SWITCH_VIDEO_MODE = key("hitl_switch_video_mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_4);
    public static final KeyMapping HITL_EXIT = key("hitl_exit", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GPS_PANEL);
        event.register(WEAPON_TEST_OVERLAY);
        event.register(ARM_SELECT_PREV);
        event.register(ARM_SELECT_NEXT);
        event.register(SET_GPS_TARGET);
        event.register(CLEAR_GPS);
        event.register(DEPLOY_DEPLOYABLE_UAV);
        event.register(SWITCH_DEPLOYABLE_UAV);
        event.register(HMD_TOGGLE);
        event.register(FIRE_CONTROL_STABILIZER);
        event.register(HITL_REDESIGNATE);
        event.register(HITL_SWITCH_VIDEO_MODE);
        event.register(HITL_EXIT);
    }
}
