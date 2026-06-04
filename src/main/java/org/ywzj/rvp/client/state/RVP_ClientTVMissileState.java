package org.ywzj.rvp.client.state;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.client.shader.TVMissileVideoPostHandler;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.network.C2STVMissileControlInput;
import org.ywzj.rvp.network.C2STVMissileExit;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

public class RVP_ClientTVMissileState {

    private static int activeMissileId = -1;
    private static boolean viewTypeCaptured;
    private static LocalVehiclePlayer.ViewType prevViewType;
    private static RVP_EnumVideoMode videoMode = RVP_EnumVideoMode.COLOR;
    private static boolean lastModeKeyDown;
    private static boolean lastExitMouseDown;
    private static float tvMissileYaw;
    private static float tvMissilePitch;

    public static boolean isActive() {
        return activeMissileId >= 0;
    }

    public static int getActiveMissileId() {
        return activeMissileId;
    }

    public static RVP_EnumVideoMode getVideoMode() {
        return videoMode;
    }

    public static float getTVMissileYaw() {
        return tvMissileYaw;
    }

    public static float getTVMissilePitch() {
        return tvMissilePitch;
    }

    public static void setActiveMissileId(int missileEntityId) {
        if (activeMissileId < 0 && missileEntityId >= 0 && !viewTypeCaptured) {
            viewTypeCaptured = true;
            prevViewType = LocalVehiclePlayer.instance.viewType;
        }
        activeMissileId = missileEntityId;
        if (missileEntityId >= 0) {
            tvMissileYaw = LocalVehiclePlayer.instance.cameraAimRotY;
            tvMissilePitch = LocalVehiclePlayer.instance.cameraAimRotX;
        }
    }

    public static void clear() {
        activeMissileId = -1;
        videoMode = RVP_EnumVideoMode.COLOR;
        TVMissileVideoPostHandler.setActive(false);
        LocalVehiclePlayer.instance.thermalImaging = false;
        tvMissileYaw = 0f;
        tvMissilePitch = 0f;
        if (viewTypeCaptured) {
            viewTypeCaptured = false;
            if (prevViewType != null) {
                LocalVehiclePlayer.instance.viewType = prevViewType;
            }
            prevViewType = null;
        }
    }

    public static void tick(Minecraft mc, LocalPlayer player) {
        if (activeMissileId < 0) {
            return;
        }
        if (mc.level == null || player == null) {
            clear();
            return;
        }
        if (!(player.getVehicle() instanceof AbstractVehicle)) {
            clear();
            return;
        }
        Entity e = mc.level.getEntity(activeMissileId);
        if (!(e instanceof RVP_MissileEntity missile)) {
            clear();
            return;
        }
        videoMode = normalizeVideoMode(missile, videoMode);
        TVMissileVideoPostHandler.setActive(videoMode == RVP_EnumVideoMode.BW);
        LocalVehiclePlayer.instance.viewType = LocalVehiclePlayer.ViewType.SCOPE;
        LocalVehiclePlayer.instance.thermalImaging = (videoMode == RVP_EnumVideoMode.THERMAL);
        if (tickExitClick(mc)) {
            RVP_Network.CHANNEL.sendToServer(C2STVMissileExit.of(activeMissileId));
            clear();
            return;
        }
        tickModeSwitch(mc);
        // Steering follows the player's free look (accumulated in applyTVMissileTurnDelta),
        // NOT the launcher turret aim: in SCOPE view cameraAimRotX/Y is clamped by the
        // weapon unit rotation limits, which would stop the missile from tracking once it
        // turns past those limits ("看向哪里就飞向哪里").
        RVP_Network.CHANNEL.sendToServer(C2STVMissileControlInput.of(
                activeMissileId,
                tvMissileYaw,
                tvMissilePitch,
                player.tickCount
        ));
    }

    public static void applyTVMissileTurnDelta(double pYRot, double pXRot) {
        float yawStep = Mth.clamp((float) (pYRot * 0.05f), -1.6f, 1.6f);
        float pitchStep = Mth.clamp((float) (pXRot * 0.05f), -1.6f, 1.6f);
        tvMissileYaw = Mth.wrapDegrees(tvMissileYaw + yawStep);
        tvMissilePitch = Mth.clamp(tvMissilePitch + pitchStep, -89.9f, 89.9f);
    }

    private static boolean tickExitClick(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean pressed = down && !lastExitMouseDown;
        lastExitMouseDown = down;
        return pressed;
    }

    private static void tickModeSwitch(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        boolean down = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_4);
        boolean pressed = down && !lastModeKeyDown;
        lastModeKeyDown = down;
        if (!pressed) {
            return;
        }
        Entity e = mc.level == null ? null : mc.level.getEntity(activeMissileId);
        if (!(e instanceof RVP_MissileEntity missile)) {
            return;
        }
        videoMode = nextVideoMode(missile, videoMode);
    }

    private static RVP_EnumVideoMode normalizeVideoMode(RVP_MissileEntity missile, RVP_EnumVideoMode preferred) {
        if (isModeAllowed(missile, preferred)) {
            return preferred;
        }
        int defaultMode = missile.rvp$getDefaultTVMissileVideoMode();
        if ((defaultMode & RVP_MissileEntity.TV_MODE_COLOR) != 0 && isModeAllowed(missile, RVP_EnumVideoMode.COLOR)) {
            return RVP_EnumVideoMode.COLOR;
        }
        if ((defaultMode & RVP_MissileEntity.TV_MODE_BW) != 0 && isModeAllowed(missile, RVP_EnumVideoMode.BW)) {
            return RVP_EnumVideoMode.BW;
        }
        if ((defaultMode & RVP_MissileEntity.TV_MODE_THERMAL) != 0 && isModeAllowed(missile, RVP_EnumVideoMode.THERMAL)) {
            return RVP_EnumVideoMode.THERMAL;
        }
        if (isModeAllowed(missile, RVP_EnumVideoMode.COLOR)) {
            return RVP_EnumVideoMode.COLOR;
        }
        if (isModeAllowed(missile, RVP_EnumVideoMode.BW)) {
            return RVP_EnumVideoMode.BW;
        }
        return RVP_EnumVideoMode.THERMAL;
    }

    private static RVP_EnumVideoMode nextVideoMode(RVP_MissileEntity missile, RVP_EnumVideoMode current) {
        RVP_EnumVideoMode[] order = RVP_EnumVideoMode.values();
        for (int i = 1; i <= order.length; i++) {
            RVP_EnumVideoMode next = order[(current.ordinal() + i) % order.length];
            if (isModeAllowed(missile, next)) {
                return next;
            }
        }
        return current;
    }

    private static boolean isModeAllowed(RVP_MissileEntity missile, RVP_EnumVideoMode mode) {
        int mask = missile.rvp$getTVMissileVideoModeMask();
        return switch (mode) {
            case COLOR -> (mask & RVP_MissileEntity.TV_MODE_COLOR) != 0;
            case BW -> (mask & RVP_MissileEntity.TV_MODE_BW) != 0;
            case THERMAL -> (mask & RVP_MissileEntity.TV_MODE_THERMAL) != 0;
        };
    }
}
