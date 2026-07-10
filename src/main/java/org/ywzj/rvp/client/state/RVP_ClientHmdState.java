package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.debug.RVP_DebugStateLogs;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

public class RVP_ClientHmdState {

    public enum HmdType {
        NONE, RADAR, IR
    }

    private static final RVP_ClientHmdState INSTANCE = new RVP_ClientHmdState();

    private static final float RADAR_HMD_HALF_FOV = 2.5f;
    private static final float HMD_RANGE_MULTIPLIER = 0.5f;
    private static final int HMD_SCAN_INTERVAL = 5;
    private static final int IR_LOCK_GRACE_TICKS = 20;
    private static final int OUT_OF_BOUNDS_TIMEOUT = 20;
    private static final float SMOOTH_FACTOR = 0.4f;

    private HmdType hmdType = HmdType.NONE;
    private int scanCounter;
    private int outOfBoundsTicks;
    private int lockedEntityId = -1;
    private int warningTicks;
    private int tickCount;

    private float smoothPitch;
    private float smoothYaw;
    private boolean smoothInitialized;

    private float irSeekerFov = 0f;
    private float irSeekerRange = 0f;
    private float irGuideHeadMaxAngle = 0f;
    private float irLockMinHeight = 4f;
    private boolean groundIr = false;

    private int irCachedLockedEntityId = -1;
    private int irLastConfirmedLockTick = Integer.MIN_VALUE;
    private int irGraceStartTick = Integer.MIN_VALUE;
    private int irGraceTargetId = -1;

    private RVP_ClientHmdState() {}

    public static RVP_ClientHmdState getInstance() {
        return INSTANCE;
    }

    public boolean isHmdMode() {
        return hmdType != HmdType.NONE;
    }

    public HmdType getHmdType() {
        return hmdType;
    }

    public boolean isRadarHmd() {
        return hmdType == HmdType.RADAR;
    }

    public boolean isIrHmd() {
        return hmdType == HmdType.IR;
    }

    public int getTickCount() {
        return tickCount;
    }

    public boolean isWarning() {
        return warningTicks > 0;
    }

    public int getLockedEntityId() {
        return lockedEntityId;
    }

    public boolean hasLock() {
        return hmdType == HmdType.IR && lockedEntityId != -1;
    }

    public Entity getLockedEntity() {
        if (lockedEntityId == -1) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        return mc.level.getEntity(lockedEntityId);
    }

    public boolean shouldKeepIrLockGrace(Entity target) {
        return hmdType == HmdType.IR
                && target != null
                && target.isAlive()
                && target.getId() == irCachedLockedEntityId
                && tickCount - irLastConfirmedLockTick <= IR_LOCK_GRACE_TICKS;
    }

    public float getSmoothPitch() {
        return smoothPitch;
    }

    public float getSmoothYaw() {
        return smoothYaw;
    }

    public float getIrSeekerFov() {
        return irSeekerFov;
    }

    public float getIrSeekerRange() {
        return irSeekerRange;
    }

    public float getIrGuideHeadMaxAngle() {
        return irGuideHeadMaxAngle;
    }

    public float getIrLockMinHeight() {
        return irLockMinHeight;
    }

    public boolean isGroundIr() {
        return groundIr;
    }

    public boolean toggle() {
        if (hmdType == HmdType.RADAR) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
            return false;
        }
        ensureRadarOn();
        hmdType = HmdType.RADAR;
        scanCounter = 0;
        outOfBoundsTicks = 0;
        warningTicks = 0;
        smoothInitialized = false;
        return true;
    }

    private void ensureRadarOn() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!radarUnit.isOn()) {
                radarUnit.toggle(true);
            }
        }
    }

    public void disable() {
        if (hmdType != HmdType.NONE) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
            clearIrLockState();
        }
    }

    public void checkIrHmd() {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            if (hmdType == HmdType.IR) {
                hmdType = HmdType.NONE;
                clearIrLockState();
            }
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            if (hmdType == HmdType.IR) {
                hmdType = HmdType.NONE;
                clearIrLockState();
            }
            return;
        }

        boolean shouldBeActive = false;
        float seekerFov = 0f;
        float seekerRange = 0f;
        float guideHeadMaxAngle = 0f;
        float lockMinHeight = 4f;

        java.util.Optional<AbstractVehicleWeapon<?>> weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isPresent() && weaponOpt.get() instanceof RVP_WeaponBase rvpWeapon) {
            RVP_WeaponData data = rvpWeapon.getData();
            if (data.isHomingProjectile()
                    && !data.isRadarHoming()
                    && !data.isAntiRadiationMissile()
                    && !data.isGpsMissile()
                    && weaponUnit.isSeekerOn()) {
                shouldBeActive = true;
                seekerFov = data.getMaxLockOnAngle();
                seekerRange = data.getMaxLockOnRange();
                guideHeadMaxAngle = data.getMaxGuideHeadAngle();
                lockMinHeight = data.getLockMinHeight();
            }
        }

        if (shouldBeActive && hmdType != HmdType.IR) {
            hmdType = HmdType.IR;
            scanCounter = 0;
            smoothInitialized = false;
            irSeekerFov = seekerFov;
            irSeekerRange = seekerRange;
            irGuideHeadMaxAngle = guideHeadMaxAngle;
            irLockMinHeight = lockMinHeight;
            groundIr = lockMinHeight < 0;
        } else if (!shouldBeActive && hmdType == HmdType.IR) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
            groundIr = false;
            clearIrLockState();
        } else if (hmdType == HmdType.IR) {
            irSeekerFov = seekerFov;
            irSeekerRange = seekerRange;
            irGuideHeadMaxAngle = guideHeadMaxAngle;
            irLockMinHeight = lockMinHeight;
            groundIr = lockMinHeight < 0;
        }
    }

    public void tick() {
        tickCount++;
        if (hmdType == HmdType.NONE) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof AbstractVehicle vehicle)) {
            disable();
            return;
        }

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            disable();
            return;
        }

        float aimPitch = LocalVehiclePlayer.instance.cameraAimRotX - LocalVehiclePlayer.CAMERA_UPWARD_ANGLE;
        float aimYaw = LocalVehiclePlayer.instance.cameraAimRotY;
        if (!smoothInitialized) {
            smoothPitch = aimPitch;
            smoothYaw = aimYaw;
            smoothInitialized = true;
        }
        smoothPitch += (aimPitch - smoothPitch) * SMOOTH_FACTOR;
        smoothYaw += (aimYaw - smoothYaw) * SMOOTH_FACTOR;

        if (hmdType == HmdType.IR && irGuideHeadMaxAngle > 0f) {
            Vec3 weaponDir = weaponUnit.worldVec();
            Vec3 hmdDir = VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
            double currentAngle = Math.toDegrees(Math.acos(
                    Math.max(-1.0, Math.min(1.0, weaponDir.dot(hmdDir)))));
            if (currentAngle > irGuideHeadMaxAngle) {
                double excess = currentAngle - irGuideHeadMaxAngle;
                float pull = (float) (1.0 - excess / currentAngle);
                smoothPitch = aimPitch + (smoothPitch - aimPitch) * pull;
                smoothYaw = aimYaw + (smoothYaw - aimYaw) * pull;
            }
        }

        Vec3 headLook = VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
        if (hmdType == HmdType.RADAR) {
            tickRadarHmd(mc, vehicle, weaponUnit, headLook);
        } else if (hmdType == HmdType.IR) {
            tickIrHmd(mc, vehicle, weaponUnit, headLook);
        }
    }

    private static RadarUnit findHmdRadar(WeaponUnit weaponUnit) {
        RadarUnit main = weaponUnit.getMainRadarUnit();
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit != main && isRadarHmsEnabled(radarUnit)) {
                return radarUnit;
            }
        }
        if (main != null && isRadarHmsEnabled(main)) {
            return main;
        }
        return main;
    }

    private static boolean isRadarHmsEnabled(RadarUnit radar) {
        Object data = ((PartUnitAccessorMixin) (Object) radar).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext.ywzj_rvp$isEnableHms();
        }
        return true;
    }

    private void tickRadarHmd(Minecraft mc, AbstractVehicle vehicle, WeaponUnit weaponUnit, Vec3 headLook) {
        RadarUnit radar = findHmdRadar(weaponUnit);
        if (radar == null) {
            disable();
            return;
        }

        boolean isScope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        Vec3 scanDir = isScope ? weaponUnit.worldVec().normalize() : headLook;
        Vec3 radarPos = radar.worldRadarPosition();
        float maxRange = radar.getMaxScanDistance() * HMD_RANGE_MULTIPLIER;

        if (++scanCounter < HMD_SCAN_INTERVAL) {
            return;
        }
        scanCounter = 0;

        if (!isWithinRadarLimits(radar, scanDir)) {
            outOfBoundsTicks++;
            warningTicks = Math.min(warningTicks + 1, OUT_OF_BOUNDS_TIMEOUT + 5);
            if (outOfBoundsTicks >= OUT_OF_BOUNDS_TIMEOUT) {
                mc.player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.hmd.out_of_bounds"), true);
                disable();
            }
            return;
        }
        outOfBoundsTicks = 0;
        warningTicks = 0;

        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        for (RadarUnit.DetectedObject obj : radar.getDetectedEntities().values()) {
            Entity entity = obj.entity;
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(radarPos);
            double dist = toTarget.length();
            if (dist > maxRange || dist < 1.0) {
                continue;
            }
            Vec3 dir = toTarget.normalize();
            double angle = Math.toDegrees(Math.acos(scanDir.dot(dir)));
            if (angle > RADAR_HMD_HALF_FOV) {
                continue;
            }
            double score = angle * 0.7 + dist * 0.0003;
            if (score < bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            radar.setLockedEntity(bestTarget);
            weaponUnit.setLockedEntity(bestTarget);
            lockedEntityId = bestTarget.getId();
            hmdType = HmdType.NONE;
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.locked"), true);
        }
    }

    private void tickIrHmd(Minecraft mc, AbstractVehicle vehicle, WeaponUnit weaponUnit, Vec3 headLook) {
        Vec3 seekerPos = weaponUnit.worldPivotPosition();
        float maxRange = irSeekerRange;
        float halfFov = irSeekerFov / 2f;

        warningTicks = 0;
        outOfBoundsTicks = 0;

        Entity tracked = resolveTrackedIrTarget(mc, weaponUnit);
        if (tracked != null) {
            Vec3 toTarget = tracked.getBoundingBox().getCenter().subtract(seekerPos);
            Vec3 dir = toTarget.normalize();
            Vec3 refDir = weaponUnit.worldVec().normalize();
            double offBoresightAngle = Math.toDegrees(Math.acos(
                    Math.max(-1.0, Math.min(1.0, refDir.dot(dir)))));
            if (!RVP_GuidanceMath.isTargetPassAltFilter(tracked, irLockMinHeight)) {
                RVP_DebugStateLogs.logIrHms("drop alt-filter target=" + tracked.getId());
                clearIrLockState(weaponUnit);
                return;
            }
            if (offBoresightAngle <= irGuideHeadMaxAngle) {
                confirmIrLock(weaponUnit, tracked, offBoresightAngle, "hold");
                return;
            }
            if (shouldKeepIrLockGrace(tracked)) {
                if (weaponUnit.getLockedEntity() == null || weaponUnit.getLockedEntity().getId() != tracked.getId()) {
                    weaponUnit.setLockedEntity(tracked);
                }
                lockedEntityId = tracked.getId();
                if (irGraceTargetId != tracked.getId()) {
                    irGraceTargetId = tracked.getId();
                    irGraceStartTick = tickCount;
                    RVP_DebugStateLogs.logIrHms("grace-start target=" + tracked.getId()
                            + " angle=" + formatAngle(offBoresightAngle)
                            + " expireIn=" + (IR_LOCK_GRACE_TICKS - (tickCount - irLastConfirmedLockTick)));
                }
                return;
            }
            RVP_DebugStateLogs.logIrHms("grace-expire target=" + tracked.getId()
                    + " angle=" + formatAngle(offBoresightAngle)
                    + " lostFor=" + (tickCount - irLastConfirmedLockTick));
            clearIrLockState(weaponUnit);
        }

        if (++scanCounter < HMD_SCAN_INTERVAL) {
            return;
        }
        scanCounter = 0;

        boolean isScope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        Vec3 scanDir = isScope ? weaponUnit.worldVec() : headLook;
        float scanHalfAngle = halfFov;

        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        var entities = mc.level.getEntities(
                vehicle,
                vehicle.getBoundingBox().inflate(maxRange),
                e -> e.isAlive() && e != mc.player && !(e instanceof AbstractVehicle v && v.isDestroyed())
        );

        for (Entity entity : entities) {
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(seekerPos);
            double dist = toTarget.length();
            if (dist > maxRange || dist < 1.0) {
                continue;
            }
            Vec3 dir = toTarget.normalize();
            double angle = Math.toDegrees(Math.acos(
                    Math.max(-1.0, Math.min(1.0, scanDir.dot(dir)))));
            if (angle > scanHalfAngle) {
                continue;
            }
            if (!RVP_GuidanceMath.isTargetPassAltFilter(entity, irLockMinHeight)) {
                continue;
            }
            double score = angle * 0.7 + dist * 0.0003;
            if (score < bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            confirmIrLock(weaponUnit, bestTarget, bestScore, "acquire");
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.ir_locked"), true);
        } else if (irGraceTargetId != -1 && tickCount - irLastConfirmedLockTick > IR_LOCK_GRACE_TICKS) {
            irGraceTargetId = -1;
            irGraceStartTick = Integer.MIN_VALUE;
        }
    }

    private Entity resolveTrackedIrTarget(Minecraft mc, WeaponUnit weaponUnit) {
        Entity locked = weaponUnit.getLockedEntity();
        if (locked != null && locked.isAlive()) {
            return locked;
        }
        if (irCachedLockedEntityId == -1 || mc.level == null) {
            return null;
        }
        Entity cached = mc.level.getEntity(irCachedLockedEntityId);
        return cached != null && cached.isAlive() ? cached : null;
    }

    private void confirmIrLock(WeaponUnit weaponUnit, Entity target, double angle, String reason) {
        if (weaponUnit.getLockedEntity() == null || weaponUnit.getLockedEntity().getId() != target.getId()) {
            weaponUnit.setLockedEntity(target);
        }
        lockedEntityId = target.getId();
        irCachedLockedEntityId = target.getId();
        irLastConfirmedLockTick = tickCount;
        if (irGraceTargetId == target.getId()) {
            RVP_DebugStateLogs.logIrHms("grace-recapture target=" + target.getId()
                    + " angle=" + formatAngle(angle)
                    + " heldFor=" + (tickCount - irGraceStartTick));
        } else {
            RVP_DebugStateLogs.logIrHms(reason + " target=" + target.getId()
                    + " angle=" + formatAngle(angle));
        }
        irGraceTargetId = -1;
        irGraceStartTick = Integer.MIN_VALUE;
    }

    private void clearIrLockState() {
        irCachedLockedEntityId = -1;
        irLastConfirmedLockTick = Integer.MIN_VALUE;
        irGraceTargetId = -1;
        irGraceStartTick = Integer.MIN_VALUE;
    }

    private void clearIrLockState(WeaponUnit weaponUnit) {
        if (weaponUnit.getLockedEntity() != null) {
            weaponUnit.setLockedEntity(null);
        }
        lockedEntityId = -1;
        clearIrLockState();
    }

    private static String formatAngle(double angle) {
        return String.format(java.util.Locale.ROOT, "%.2f", angle);
    }

    private static boolean isWithinRadarLimits(RadarUnit radar, Vec3 headLook) {
        Vec3 radarPos = radar.worldRadarPosition();
        Vec3 aimPoint = radarPos.add(headLook.scale(100));
        Vec2 localRot = radar.worldVecToLocalRot(aimPoint.subtract(radarPos));

        float yRot = (float) localRot.y;
        float yMin = radar.getYRotMin();
        float yMax = radar.getYRotMax();
        if (yMax - yMin < 360f && yMin >= 0f && yMax > 180f && yRot < 0f) {
            yRot += 360f;
        }
        if (yMax - yMin < 360f && (yRot < yMin || yRot > yMax)) {
            return false;
        }
        float xRot = (float) localRot.x;
        float radarXRot = radar.getXRot();
        float halfSector = radar.getScanSectorAngle() / 2f;
        return Math.abs(xRot - radarXRot) <= halfSector;
    }
}
