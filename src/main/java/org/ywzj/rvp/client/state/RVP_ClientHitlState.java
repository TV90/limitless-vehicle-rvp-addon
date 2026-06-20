package org.ywzj.rvp.client.state;

import com.mojang.blaze3d.platform.InputConstants;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.client.shader.TVMissileVideoPostHandler;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.network.C2SExitHitlView;
import org.ywzj.rvp.network.C2SHitlDesignate;
import org.ywzj.rvp.network.C2SHitlSteeringInput;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public class RVP_ClientHitlState {

    private static final int ENTITY_WAIT_TICKS = 80;
    /** Shared range for HUD crosshair, designation, and virtual sky aim points. */
    public static final double DESIGNATE_AIM_RANGE = 1200.0;

    private static int activeMissileId = -1;
    private static RVP_EnumHitlControlMode controlMode = RVP_EnumHitlControlMode.VIEW;
    private static int entityWaitTicks;
    private static boolean missileEntitySeen;
    private static int controlSeq;
    private static boolean viewTypeCaptured;
    private static LocalVehiclePlayer.ViewType prevViewType;
    private static RVP_EnumVideoMode videoMode = RVP_EnumVideoMode.COLOR;
    private static boolean lastModeKeyDown;
    private static boolean lastExitMouseDown;
    private static float hitlYaw;
    private static float hitlPitch;
    private static float lookOffsetYaw;
    private static float lookOffsetPitch;
    private static float displayLookOffsetYaw;
    private static float displayLookOffsetPitch;
    private static final float LOOK_OFFSET_TAU = 0.055f;
    private static boolean initialDesignateSent;
    private static boolean lastDesignateKeyDown;
    @Nullable
    private static Vec3 clientDesignatedPos;
    private static int clientDesignatedEntityId = -1;
    private static boolean hitlLinkBlocked;
    private static boolean hitlLinkSevered;
    @Nullable
    private static Vec3 clientAimPoint;
    /** Updated once per client tick for hot-path particle suppression (no getEntity in particle spawn). */
    private static boolean particleSuppressActive;
    private static double particleSuppressX;
    private static double particleSuppressY;
    private static double particleSuppressZ;

    public static boolean isActive() {
        return activeMissileId >= 0;
    }

    public static boolean isMouseSteering() {
        return isActive() && controlMode == RVP_EnumHitlControlMode.MOUSE;
    }

    public static boolean isDesignateMode() {
        return isActive() && controlMode == RVP_EnumHitlControlMode.DESIGNATE;
    }

    public static float getLookOffsetYaw() {
        return isDesignateMode() ? displayLookOffsetYaw : lookOffsetYaw;
    }

    public static float getLookOffsetPitch() {
        return isDesignateMode() ? displayLookOffsetPitch : lookOffsetPitch;
    }

    /** Frame-rate smoothing for SACLOS+TV crosshair offset (called from camera pose update). */
    public static void tickDesignateLookOffset(float dtSeconds) {
        if (!isDesignateMode()) {
            displayLookOffsetYaw = lookOffsetYaw;
            displayLookOffsetPitch = lookOffsetPitch;
            return;
        }
        if (dtSeconds <= 0f) {
            displayLookOffsetYaw = lookOffsetYaw;
            displayLookOffsetPitch = lookOffsetPitch;
            return;
        }
        float alpha = 1f - (float) Math.exp(-dtSeconds / LOOK_OFFSET_TAU);
        displayLookOffsetYaw = Mth.wrapDegrees(
                displayLookOffsetYaw + Mth.wrapDegrees(lookOffsetYaw - displayLookOffsetYaw) * alpha);
        displayLookOffsetPitch += (lookOffsetPitch - displayLookOffsetPitch) * alpha;
    }

    public static int getActiveMissileId() {
        return activeMissileId;
    }

    public static int getClientDesignatedEntityId() {
        return clientDesignatedEntityId;
    }

    public static boolean isHitlLinkBlocked() {
        return hitlLinkBlocked;
    }

    public static void onHitlLinkState(int missileEntityId, boolean blocked, boolean severed) {
        if (missileEntityId != activeMissileId) {
            return;
        }
        hitlLinkBlocked = blocked;
        hitlLinkSevered = severed;
        if (hitlLinkSevered) {
            clear();
        }
    }

    public static boolean shouldHideActiveMissileVfx(Entity entity) {
        return entity != null && isActive() && entity.getId() == activeMissileId;
    }

    public static boolean shouldSuppressParticleNearActiveMissile(double x, double y, double z) {
        if (!particleSuppressActive) {
            return false;
        }
        double dx = x - particleSuppressX;
        double dy = y - particleSuppressY;
        double dz = z - particleSuppressZ;
        return dx * dx + dy * dy + dz * dz <= 20.25D;
    }

    public static RVP_EnumHitlControlMode getControlMode() {
        return controlMode;
    }

    public static RVP_EnumVideoMode getVideoMode() {
        return videoMode;
    }

    public static float getHitlYaw() {
        return hitlYaw;
    }

    public static float getHitlPitch() {
        return hitlPitch;
    }

    public static void enter(int missileEntityId, RVP_EnumHitlControlMode mode) {
        RVP_ClientHitlCamera.reset();
        if (activeMissileId < 0 && missileEntityId >= 0 && !viewTypeCaptured) {
            viewTypeCaptured = true;
            prevViewType = LocalVehiclePlayer.instance.viewType;
        }
        activeMissileId = missileEntityId;
        controlMode = mode != null ? mode : RVP_EnumHitlControlMode.VIEW;
        entityWaitTicks = 0;
        missileEntitySeen = false;
        controlSeq = 0;
        hitlYaw = 0f;
        hitlPitch = 0f;
        lookOffsetYaw = 0f;
        lookOffsetPitch = 0f;
        displayLookOffsetYaw = 0f;
        displayLookOffsetPitch = 0f;
        initialDesignateSent = false;
        lastDesignateKeyDown = false;
        clientDesignatedPos = null;
        clientDesignatedEntityId = -1;
        hitlLinkBlocked = false;
        hitlLinkSevered = false;
        clientAimPoint = null;
        clearParticleSuppressCache();
    }

    public static void clear() {
        RVP_ClientHitlCamera.reset();
        activeMissileId = -1;
        controlMode = RVP_EnumHitlControlMode.VIEW;
        entityWaitTicks = 0;
        missileEntitySeen = false;
        controlSeq = 0;
        videoMode = RVP_EnumVideoMode.COLOR;
        TVMissileVideoPostHandler.setActive(false);
        LocalVehiclePlayer.instance.thermalImaging = false;
        hitlYaw = 0f;
        hitlPitch = 0f;
        lookOffsetYaw = 0f;
        lookOffsetPitch = 0f;
        displayLookOffsetYaw = 0f;
        displayLookOffsetPitch = 0f;
        initialDesignateSent = false;
        lastDesignateKeyDown = false;
        clientDesignatedPos = null;
        clientDesignatedEntityId = -1;
        hitlLinkBlocked = false;
        hitlLinkSevered = false;
        clientAimPoint = null;
        clearParticleSuppressCache();
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
            clearParticleSuppressCache();
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
        RVP_MissileEntity missile = e instanceof RVP_MissileEntity m ? m : null;
        if (missile != null) {
            if (missile.isRemoved()) {
                clear();
                return;
            }
            if (!missileEntitySeen) {
                hitlYaw = missile.getYRot();
                hitlPitch = missile.getXRot();
                RVP_ClientHitlCamera.onMissileAcquired(missile);
                missileEntitySeen = true;
                if (isDesignateMode()) {
                    redesignateTargetAtCrosshair(mc, missile);
                    initialDesignateSent = true;
                }
            }
            entityWaitTicks = 0;
            videoMode = normalizeVideoMode(missile, videoMode);
            updateParticleSuppressCache(missile);
        } else if (missileEntitySeen) {
            clear();
            return;
        } else {
            clearParticleSuppressCache();
            if (++entityWaitTicks > ENTITY_WAIT_TICKS) {
                clear();
                return;
            }
        }

        TVMissileVideoPostHandler.setActive(videoMode == RVP_EnumVideoMode.BW);
        LocalVehiclePlayer.instance.viewType = LocalVehiclePlayer.ViewType.SCOPE;
        LocalVehiclePlayer.instance.thermalImaging = (videoMode == RVP_EnumVideoMode.THERMAL);

        if (tickExitClick(mc)) {
            RVP_Network.CHANNEL.sendToServer(C2SExitHitlView.of(activeMissileId));
            clear();
            return;
        }
        if (missile != null) {
            RVP_ClientHitlCamera.tickSeekerBody(missile);
            tickScopeAim(mc, missile);
            tickDesignateKey(mc, missile);
            tickModeSwitch(mc);
        }
        if (controlMode == RVP_EnumHitlControlMode.MOUSE) {
            if (hitlLinkBlocked) {
                return;
            }
            controlSeq++;
            RVP_Network.CHANNEL.sendToServer(C2SHitlSteeringInput.of(
                    activeMissileId, hitlYaw, hitlPitch, controlSeq));
        }
    }

    public static void applySteeringDelta(double pYRot, double pXRot) {
        if (hitlLinkBlocked) {
            return;
        }
        float yawStep = Mth.clamp((float) (pYRot * 0.15f), -4.0f, 4.0f);
        float pitchStep = Mth.clamp((float) (pXRot * 0.15f), -4.0f, 4.0f);
        hitlYaw = Mth.wrapDegrees(hitlYaw + yawStep);
        hitlPitch = Mth.clamp(hitlPitch + pitchStep, -89.9f, 89.9f);
    }

    /** BF2-style TV: offset crosshair within seeker FOV relative to missile body. */
    public static void applyLookOffsetDelta(double pYRot, double pXRot, float maxOffsetDeg) {
        if (hitlLinkBlocked) {
            return;
        }
        float yawStep = Mth.clamp((float) (pYRot * 0.15f), -4.0f, 4.0f);
        float pitchStep = Mth.clamp((float) (pXRot * 0.15f), -4.0f, 4.0f);
        float limit = Math.max(maxOffsetDeg, 1f);
        lookOffsetYaw = Mth.clamp(lookOffsetYaw + yawStep, -limit, limit);
        lookOffsetPitch = Mth.clamp(lookOffsetPitch + pitchStep, -limit, limit);
    }

    @Nullable
    public static Vec3 getClientDesignatedPos() {
        return clientDesignatedPos;
    }

    @Nullable
    public static Vec3 getClientAimPoint() {
        return clientAimPoint;
    }

    /** Press R in TV SACLOS: designate / re-designate target at current crosshair (never turns guidance off). */
    public static void redesignateTargetAtCrosshair(Minecraft mc, RVP_MissileEntity missile) {
        float aimYaw = RVP_ClientHitlCamera.getAimYaw();
        float aimPitch = RVP_ClientHitlCamera.getAimPitch();
        HitResult hit = RVP_ClientHitlUtil.raycastFromBodyAim(
                mc, missile, aimYaw, aimPitch, DESIGNATE_AIM_RANGE, true);
        if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit
                && entityHit.getEntity() != null) {
            int entityId = entityHit.getEntity().getId();
            Vec3 point = entityHit.getEntity().getBoundingBox().getCenter();
            clientDesignatedPos = point;
            if (clientDesignatedEntityId == entityId) {
                clientDesignatedEntityId = -1;
                RVP_Network.CHANNEL.sendToServer(C2SHitlDesignate.point(activeMissileId, point));
                return;
            }
            clientDesignatedEntityId = entityId;
            RVP_Network.CHANNEL.sendToServer(C2SHitlDesignate.entity(activeMissileId, entityId, point));
            return;
        }
        Vec3 point = resolveLiveAimPoint(mc, missile);
        if (point == null) {
            return;
        }
        clientDesignatedPos = point;
        clientDesignatedEntityId = -1;
        RVP_Network.CHANNEL.sendToServer(C2SHitlDesignate.point(activeMissileId, point));
    }

    @Nullable
    public static Vec3 resolveLiveAimPoint(Minecraft mc, RVP_MissileEntity missile) {
        return RVP_ClientHitlUtil.resolveAimPoint(
                mc, missile,
                RVP_ClientHitlCamera.getAimYaw(),
                RVP_ClientHitlCamera.getAimPitch(),
                DESIGNATE_AIM_RANGE,
                false);
    }

    private static void tickDesignateKey(Minecraft mc, RVP_MissileEntity missile) {
        if (!isDesignateMode()) {
            return;
        }
        if (hitlLinkBlocked) {
            return;
        }
        long window = mc.getWindow().getWindow();
        boolean down = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_R);
        boolean pressed = down && !lastDesignateKeyDown;
        lastDesignateKeyDown = down;
        if (pressed) {
            redesignateTargetAtCrosshair(mc, missile);
        }
    }

    private static void tickScopeAim(Minecraft mc, RVP_MissileEntity missile) {
        Vec3 aimPoint = resolveLiveAimPoint(mc, missile);
        if (aimPoint == null) {
            return;
        }
        clientAimPoint = aimPoint;

        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        WeaponUnit weaponUnit = lvp.getWeaponUnit();
        if (weaponUnit != null) {
            weaponUnit.weaponHitPosO = aimPoint;
            weaponUnit.weaponHitPos = aimPoint;
        }
        lvp.aimLocationDistance = missile.position().distanceTo(aimPoint);
        lvp.outOfRangeFinding = false;
    }

    private static void updateParticleSuppressCache(RVP_MissileEntity missile) {
        particleSuppressActive = true;
        particleSuppressX = missile.getX();
        particleSuppressY = missile.getY();
        particleSuppressZ = missile.getZ();
    }

    private static void clearParticleSuppressCache() {
        particleSuppressActive = false;
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
        Entity entity = mc.level == null ? null : mc.level.getEntity(activeMissileId);
        if (!(entity instanceof RVP_MissileEntity missile)) {
            return;
        }
        videoMode = nextVideoMode(missile, videoMode);
    }

    private static RVP_EnumVideoMode normalizeVideoMode(RVP_MissileEntity missile, RVP_EnumVideoMode preferred) {
        if (isModeAllowed(missile, preferred)) {
            return preferred;
        }
        int defaultMode = missile.rvp$getDefaultHitlVideoMode();
        if ((defaultMode & RVP_MissileEntity.HITL_MODE_COLOR) != 0 && isModeAllowed(missile, RVP_EnumVideoMode.COLOR)) {
            return RVP_EnumVideoMode.COLOR;
        }
        if ((defaultMode & RVP_MissileEntity.HITL_MODE_BW) != 0 && isModeAllowed(missile, RVP_EnumVideoMode.BW)) {
            return RVP_EnumVideoMode.BW;
        }
        if ((defaultMode & RVP_MissileEntity.HITL_MODE_THERMAL) != 0 && isModeAllowed(missile, RVP_EnumVideoMode.THERMAL)) {
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
        int mask = missile.rvp$getHitlVideoModeMask();
        return switch (mode) {
            case COLOR -> (mask & RVP_MissileEntity.HITL_MODE_COLOR) != 0;
            case BW -> (mask & RVP_MissileEntity.HITL_MODE_BW) != 0;
            case THERMAL -> (mask & RVP_MissileEntity.HITL_MODE_THERMAL) != 0;
        };
    }
}
