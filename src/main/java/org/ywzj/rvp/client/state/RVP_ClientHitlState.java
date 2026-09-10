package org.ywzj.rvp.client.state;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.shader.TVMissileVideoPostHandler;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.network.C2SExitHitlView;
import org.ywzj.rvp.network.C2SHitlDesignate;
import org.ywzj.rvp.network.C2SHitlDetonate;
import org.ywzj.rvp.network.C2SHitlSteeringInput;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public class RVP_ClientHitlState {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int ENTITY_WAIT_TICKS = 80;
    private static final int VEHICLE_WAIT_TICKS = 8;
    private static final int EXIT_CLICK_GUARD_TICKS = 4;
    /** 虚拟摇杆偏移的每 tick 衰减系数：0.6 ≈ 半衰期 2.6 tick（约 0.13 秒），停手约 0.3 秒回直飞。 */
    private static final float STEER_OFFSET_DECAY = 0.6f;
    /** 右键被 RVP 消费（空爆引爆/退出视角）后的抑制窗口 tick，期间拦截本体右键放大。 */
    private static int rightClickGuardUntilTick = Integer.MIN_VALUE;
    /** Shared range for HUD crosshair, designation, and virtual sky aim points. */
    public static final double DESIGNATE_AIM_RANGE = 1200.0;

    private static int activeMissileId = -1;
    private static RVP_EnumHitlControlMode controlMode = RVP_EnumHitlControlMode.VIEW;
    private static int entityWaitTicks;
    private static int vehicleWaitTicks;
    private static int exitClickGuardTicks;
    private static boolean missileEntitySeen;
    private static int controlSeq;
    private static boolean viewTypeCaptured;
    private static LocalVehiclePlayer.ViewType prevViewType;
    private static RVP_EnumVideoMode videoMode = RVP_EnumVideoMode.COLOR;
    private static boolean videoModeUserSelected;
    private static float hitlYaw;
    private static float hitlPitch;
    private static float lookOffsetYaw;
    private static float lookOffsetPitch;
    private static float displayLookOffsetYaw;
    private static float displayLookOffsetPitch;
    private static final float LOOK_OFFSET_TAU = 0.055f;
    private static boolean initialDesignateSent;
    @Nullable
    private static Vec3 clientDesignatedPos;
    private static int clientDesignatedEntityId = -1;
    private static boolean hitlLinkBlocked;
    private static boolean hitlLinkSevered;
    /** DIRCM 对 HITL 弹的临时干扰状态（服务端 S2CHitlLinkState 同步，驱动白闪滤镜）。 */
    private static boolean dircmJam;
    private static int dircmJamRemainTick;
    private static int dircmJamTotalTick;
    @Nullable
    private static Vec3 clientAimPoint;
    /** Updated once per client tick for hot-path particle suppression (no getEntity in particle spawn). */
    private static boolean particleSuppressActive;
    private static double particleSuppressX;
    private static double particleSuppressY;
    private static double particleSuppressZ;
    /**
     * 抑制球半径平方（动态）：尾迹生成点在弹体后 missle_native_trail_offset（默认 3 格），
     * 高速弹单 tick 位移可达数格——固定 4.5 格球会漏抑制尾段，按"弹速 + 偏移 + 余量"扩展。
     */
    private static double particleSuppressRadiusSq = 20.25D;

    public static boolean isActive() {
        return activeMissileId >= 0;
    }

    public static void markRightClickConsumed(long gameTime) {
        rightClickGuardUntilTick = (int) Math.max(rightClickGuardUntilTick, gameTime + 2);
    }

    public static boolean isRightClickGuardActive(long gameTime) {
        return gameTime <= rightClickGuardUntilTick;
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

    public static void onHitlDircmJam(int missileEntityId, boolean jam, int remainTick, int totalTick) {
        if (missileEntityId != activeMissileId) {
            return;
        }
        dircmJam = jam;
        dircmJamRemainTick = jam ? Math.max(0, remainTick) : 0;
        dircmJamTotalTick = jam ? Math.max(1, totalTick) : 0;
    }

    /** 是否正被 DIRCM 干扰（HITL 白闪滤镜驱动，与雪花互斥）。 */
    public static boolean isDircmJammed() {
        return dircmJam && !hitlLinkBlocked;
    }

    /** DIRCM 干扰剩余 tick（客户端白闪消退计时）。 */
    public static int getDircmJamRemainTick() {
        return dircmJamRemainTick;
    }

    /** DIRCM 干扰总时长 tick（白闪进度计算基准）。 */
    public static int getDircmJamTotalTick() {
        return dircmJamTotalTick;
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
        return dx * dx + dy * dy + dz * dz <= particleSuppressRadiusSq;
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
        boolean missileChanged = activeMissileId != missileEntityId;
        RVP_ClientHitlCamera.reset();
        if (activeMissileId < 0 && missileEntityId >= 0 && !viewTypeCaptured) {
            viewTypeCaptured = true;
            prevViewType = LocalVehiclePlayer.instance.viewType;
        }
        activeMissileId = missileEntityId;
        controlMode = mode != null ? mode : RVP_EnumHitlControlMode.VIEW;
        entityWaitTicks = 0;
        vehicleWaitTicks = 0;
        exitClickGuardTicks = EXIT_CLICK_GUARD_TICKS;
        missileEntitySeen = false;
        controlSeq = 0;
        hitlYaw = 0f;
        hitlPitch = 0f;
        lookOffsetYaw = 0f;
        lookOffsetPitch = 0f;
        displayLookOffsetYaw = 0f;
        displayLookOffsetPitch = 0f;
        initialDesignateSent = false;
        clientDesignatedPos = null;
        clientDesignatedEntityId = -1;
        hitlLinkBlocked = false;
        hitlLinkSevered = false;
        dircmJam = false;
        dircmJamRemainTick = 0;
        dircmJamTotalTick = 0;
        clientAimPoint = null;
        // 预激活发射点粒子抑制（替换原 clear）：枪口烟/火花 burst 早于导弹实体同步到达，
        // 见 preactivateParticleSuppressFromVehicle 注释
        preactivateParticleSuppressFromVehicle();
        if (missileChanged) {
            videoMode = RVP_EnumVideoMode.COLOR;
            videoModeUserSelected = false;
        }
        while (RVP_Keys.HITL_EXIT.consumeClick()) {
            // Drain the fire-click residue when TV enter is bound to the same mouse button as exit.
        }
    }

    public static void clear() {
        RVP_ClientHitlCamera.reset();
        activeMissileId = -1;
        controlMode = RVP_EnumHitlControlMode.VIEW;
        entityWaitTicks = 0;
        vehicleWaitTicks = 0;
        exitClickGuardTicks = 0;
        missileEntitySeen = false;
        controlSeq = 0;
        videoMode = RVP_EnumVideoMode.COLOR;
        videoModeUserSelected = false;
        TVMissileVideoPostHandler.setActive(false);
        LocalVehiclePlayer.instance.thermalImaging = false;
        hitlYaw = 0f;
        hitlPitch = 0f;
        lookOffsetYaw = 0f;
        lookOffsetPitch = 0f;
        displayLookOffsetYaw = 0f;
        displayLookOffsetPitch = 0f;
        initialDesignateSent = false;
        clientDesignatedPos = null;
        clientDesignatedEntityId = -1;
        hitlLinkBlocked = false;
        hitlLinkSevered = false;
        dircmJam = false;
        dircmJamRemainTick = 0;
        dircmJamTotalTick = 0;
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
            if (++vehicleWaitTicks > VEHICLE_WAIT_TICKS) {
                clear();
                return;
            }
        } else {
            vehicleWaitTicks = 0;
        }

        Entity e = mc.level.getEntity(activeMissileId);
        RVP_MissileEntity missile = e instanceof RVP_MissileEntity m ? m : null;
        if (missile != null) {
            if (missile.isRemoved()) {
                clear();
                return;
            }
            if (!missileEntitySeen) {
                // 虚拟摇杆：偏移量以 0 为基准（直飞），不继承弹体朝向——
                // 导弹出膛后保持沿出膛方向直飞，直到玩家施加转向偏移
                hitlYaw = 0f;
                hitlPitch = 0f;
                RVP_ClientHitlCamera.onMissileAcquired(missile);
                missileEntitySeen = true;
                if (!videoModeUserSelected) {
                    videoMode = resolveInitialVideoMode(missile);
                }
                if (isDesignateMode()) {
                    redesignateTargetAtCrosshair(mc, missile);
                    initialDesignateSent = true;
                }
            }
            entityWaitTicks = 0;
            videoMode = videoModeUserSelected
                    ? normalizeVideoMode(missile, videoMode)
                    : resolveInitialVideoMode(missile);
            updateParticleSuppressCache(missile);
        } else if (missileEntitySeen) {
            clear();
            return;
        } else {
            // 导弹实体同步等待窗口：保留 enter() 预激活的发射点抑制球，
            // 覆盖枪口烟 burst；超时仍无实体才整体 clear
            if (++entityWaitTicks > ENTITY_WAIT_TICKS) {
                clear();
                return;
            }
        }

        TVMissileVideoPostHandler.setActive(videoMode == RVP_EnumVideoMode.BW);
        LocalVehiclePlayer.instance.viewType = LocalVehiclePlayer.ViewType.SCOPE;
        LocalVehiclePlayer.instance.thermalImaging = (videoMode == RVP_EnumVideoMode.THERMAL);

        if (tickExitClick(mc)) {
            if (mc.level != null) {
                markRightClickConsumed(mc.level.getGameTime());
            }
            // 右键：默认退出视角；武器配置 hitl_right_click_detonate 时改为提前引爆
            Entity hitlMissile = mc.level == null ? null : mc.level.getEntity(activeMissileId);
            if (hitlMissile instanceof RVP_MissileEntity rvpMissile
                    && rvpMissile.rvp$isHitlRightClickDetonate()) {
                RVP_Network.CHANNEL.sendToServer(C2SHitlDetonate.of(activeMissileId));
            } else {
                RVP_Network.CHANNEL.sendToServer(C2SExitHitlView.of(activeMissileId));
            }
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
            // 虚拟摇杆自动回中：偏移每 tick 指数衰减回直飞（半衰期约 0.4 秒）。
            // 持续甩鼠标 = 持续压着偏移转圈；停手 = 导弹自动缓缓回直飞，
            // 无需精确反向甩鼠标回中（修复"一转弯就直不回来"的手感问题）。
            hitlYaw = Mth.wrapDegrees(hitlYaw * STEER_OFFSET_DECAY);
            hitlPitch *= STEER_OFFSET_DECAY;
            if (hitlLinkBlocked) {
                // HITL 转向链诊断：链路阻断时转向输入被丢弃（不发 C2S），日志留痕供对照
                if (RVP_DebugFlags.HITL.isEnabled()) {
                    LOGGER.info("[RVP-HITL][C] 链路阻断，未发送 yaw={} pitch={}",
                            Mth.wrapDegrees(hitlYaw), hitlPitch);
                }
                return;
            }
            controlSeq++;
            RVP_Network.CHANNEL.sendToServer(C2SHitlSteeringInput.of(
                    activeMissileId, hitlYaw, hitlPitch, controlSeq));
            if (RVP_DebugFlags.HITL.isEnabled()) {
                LOGGER.info("[RVP-HITL][C] 发送 seq={} yaw={} pitch={}",
                        controlSeq, Mth.wrapDegrees(hitlYaw), hitlPitch);
            }
        }
    }

    public static void applySteeringDelta(double pYRot, double pXRot) {
        if (hitlLinkBlocked) {
            return;
        }
        float yawStep = Mth.clamp((float) (pYRot * 0.15f), -4.0f, 4.0f);
        float pitchStep = Mth.clamp((float) (pXRot * 0.15f), -4.0f, 4.0f);
        // 虚拟摇杆：偏移量钳制 ±45°（与 max_guidance_angle 对齐，保证指令方向不超锥角、无死区）
        hitlYaw = Mth.clamp(Mth.wrapDegrees(hitlYaw + yawStep), -45.0f, 45.0f);
        hitlPitch = Mth.clamp(hitlPitch + pitchStep, -45.0f, 45.0f);
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
        while (RVP_Keys.HITL_REDESIGNATE.consumeClick()) {
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
        // 动态抑制半径：尾迹生成点在弹体后（missle_native_trail_offset 默认 3 格），高速弹
        // 单 tick 位移可达数格——固定 4.5 格球会漏抑制尾段，按"弹速 + 偏移 + 余量"扩展
        particleSuppressRadiusSq = Math.max(20.25D,
                Math.pow(missile.getDeltaMovement().length() + 5.5D, 2));
    }

    private static void clearParticleSuppressCache() {
        particleSuppressActive = false;
        particleSuppressRadiusSq = 20.25D;
    }

    /**
     * 进入导弹视角时用<b>发射车位置</b>立即预激活粒子抑制球。
     *
     * <p>时序：服务端先发 {@code S2CEnterHitlView}、后发开火广播包，而导弹实体的
     * AddEntity 同步在 tick 末才到达——期间本体枪口烟/火花 burst
     * （{@code AbstractVehicleWeapon.onClientFire}）已喷出，若屏蔽球未激活则永久漏出
     * （burst 寿命仅 5/10 tick）。出膛点在发射车武器站上，用载具位置 + 放大半径
     * （8 格）覆盖；导弹实体同步后由 {@link #updateParticleSuppressCache} 切回导弹跟随。</p>
     */
    private static void preactivateParticleSuppressFromVehicle() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || lvp.vehicle == null) {
            clearParticleSuppressCache();
            return;
        }
        particleSuppressActive = true;
        particleSuppressX = lvp.vehicle.getX();
        particleSuppressY = lvp.vehicle.getY();
        particleSuppressZ = lvp.vehicle.getZ();
        particleSuppressRadiusSq = 64.0D;
    }

    private static boolean tickExitClick(Minecraft mc) {
        if (exitClickGuardTicks > 0) {
            exitClickGuardTicks--;
            while (RVP_Keys.HITL_EXIT.consumeClick()) {
                // Swallow delayed clicks from the launch press during the short post-enter guard window.
            }
            return false;
        }
        return RVP_Keys.HITL_EXIT.consumeClick();
    }

    private static void tickModeSwitch(Minecraft mc) {
        if (!RVP_Keys.HITL_SWITCH_VIDEO_MODE.consumeClick()) {
            return;
        }
        Entity entity = mc.level == null ? null : mc.level.getEntity(activeMissileId);
        if (!(entity instanceof RVP_MissileEntity missile)) {
            return;
        }
        videoMode = nextVideoMode(missile, videoMode);
        videoModeUserSelected = true;
    }

    private static RVP_EnumVideoMode normalizeVideoMode(RVP_MissileEntity missile, RVP_EnumVideoMode preferred) {
        if (isModeAllowed(missile, preferred)) {
            return preferred;
        }
        return resolveInitialVideoMode(missile);
    }

    private static RVP_EnumVideoMode resolveInitialVideoMode(RVP_MissileEntity missile) {
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
