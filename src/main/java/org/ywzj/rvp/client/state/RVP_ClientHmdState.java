package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.debug.RVP_DebugStateLogs;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.guidance.RVP_IrHudProfile;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.radar.RVP_RadarHmsMode;
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
    private boolean irUsesNewLaunchData;
    private RVP_WeaponData irLaunchWeapon;
    private RVP_IrHudProfile irHudProfile = RVP_IrHudProfile.AIR;

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

    public boolean isRadarOnlyAcm(RadarUnit radarUnit) {
        if (radarUnit == null) {
            return false;
        }
        Object data = ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        return data instanceof RadarUnitDataExt ext && ext.ywzj_rvp$isOnlyAcmHms();
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
        return resolveIrHudProfile() == RVP_IrHudProfile.GROUND;
    }

    public boolean isMixedIr() {
        return resolveIrHudProfile() == RVP_IrHudProfile.MIXED;
    }

    public RadarUnit getRadarHmdUnit(WeaponUnit weaponUnit) {
        return findHmdRadar(weaponUnit);
    }

    public Vec3 resolveRadarAimDir(WeaponUnit weaponUnit, RadarUnit radarUnit) {
        if (weaponUnit == null) {
            return VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
        }
        boolean scope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        if (scope || isRadarOnlyAcm(radarUnit)) {
            Vec3 boresight = weaponUnit.worldVec();
            return boresight.lengthSqr() > 1.0E-6 ? boresight.normalize() : VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
        }
        return VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
    }

    public boolean toggle() {
        if (hmdType == HmdType.RADAR) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || findHmdRadar(weaponUnit) == null) {
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
            if (isRadarHmsEnabled(radarUnit) && !radarUnit.isOn()) {
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
        boolean usesNewLaunchData = false;
        RVP_IrHudProfile nextHudProfile = RVP_IrHudProfile.AIR;
        RVP_WeaponData nextLaunchWeapon = null;

        java.util.Optional<AbstractVehicleWeapon<?>> weaponOpt = weaponUnit.getCurrentWeapon();
        AbstractVehicleWeapon<?> currentWeapon = RVP_LaserWeapons.unwrap(weaponOpt.orElse(null));
        if (currentWeapon instanceof RVP_WeaponBase rvpWeapon) {
            RVP_WeaponData data = rvpWeapon.getData();
            if (data.isHomingProjectile()
                    && !data.isRadarHoming()
                    && !data.isAntiRadiationMissile()
                    && !data.isGpsMissile()
                    && data.isEnableIrHmd()
                    && weaponUnit.isSeekerOn()) {
                shouldBeActive = true;
                seekerFov = data.resolveLaunchSeekerFullFov();
                seekerRange = data.resolveLaunchLockRange();
                guideHeadMaxAngle = data.resolveLaunchOffAxisLockAngle();
                usesNewLaunchData = true;
                nextLaunchWeapon = data;
                nextHudProfile = RVP_IrHudProfile.resolve(RVP_IrLockHelper.getLaunchAltitudeRange(data));
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
            groundIr = nextHudProfile == RVP_IrHudProfile.GROUND;
            irUsesNewLaunchData = usesNewLaunchData;
            irLaunchWeapon = nextLaunchWeapon;
            irHudProfile = nextHudProfile;
        } else if (!shouldBeActive && hmdType == HmdType.IR) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
            groundIr = false;
            irUsesNewLaunchData = false;
            irLaunchWeapon = null;
            irHudProfile = RVP_IrHudProfile.AIR;
            clearIrLockState();
        } else if (hmdType == HmdType.IR) {
            irSeekerFov = seekerFov;
            irSeekerRange = seekerRange;
            irGuideHeadMaxAngle = guideHeadMaxAngle;
            irLockMinHeight = lockMinHeight;
            groundIr = nextHudProfile == RVP_IrHudProfile.GROUND;
            irUsesNewLaunchData = usesNewLaunchData;
            irLaunchWeapon = nextLaunchWeapon;
            irHudProfile = nextHudProfile;
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
            Vec3 weaponDir = RVP_IrLockHelper.resolveIrBoresightDir(weaponUnit);
            Vec3 hmdDir = VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
            if (weaponDir.lengthSqr() > 1.0E-6) {
                double currentAngle = angleBetweenDeg(weaponDir, hmdDir);
                if (currentAngle > irGuideHeadMaxAngle) {
                    Vec3 limitedDir = clampDirectionToCone(weaponDir, hmdDir, irGuideHeadMaxAngle);
                    Vec2 limitedRot = VectorUtil.vecToRot(limitedDir);
                    smoothPitch = limitedRot.x;
                    smoothYaw = limitedRot.y;
                }
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
        return null;
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

        Vec3 scanDir = resolveRadarAimDir(weaponUnit, radar);
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
            if (irUsesNewLaunchData && irLaunchWeapon != null
                    && !RVP_IrLockHelper.isTargetWithinHoldEnvelope(weaponUnit, tracked, irLaunchWeapon)) {
                RVP_DebugStateLogs.logIrHms("drop new-launch-envelope target=" + tracked.getId());
                clearIrLockState(weaponUnit);
                return;
            }
            Vec3 toTarget = tracked.getBoundingBox().getCenter().subtract(seekerPos);
            double dist = toTarget.length();
            if (dist > maxRange || dist < 1.0) {
                RVP_DebugStateLogs.logIrHms("drop range target=" + tracked.getId() + " dist=" + formatAngle(dist));
                clearIrLockState(weaponUnit);
                return;
            }
            Vec3 dir = toTarget.normalize();
            Vec3 refDir = RVP_IrLockHelper.resolveIrBoresightDir(weaponUnit);
            double offBoresightAngle = angleBetweenDeg(refDir, dir);
            if (!irUsesNewLaunchData && !RVP_IrLockHelper.isTargetWithinLimits(
                    weaponUnit,
                    tracked,
                    refDir,
                    180f,
                    maxRange,
                    irLockMinHeight
            )) {
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
        // O(实体) 遍历已加载实体，替代 ±maxRange（雷达扫描距离可达数千格）立方体 getEntities
        // （客户端 HMD IR 扫描掉帧）；maxRange 距离闸门保留在下方循环内
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == vehicle || !entity.isAlive() || entity == mc.player
                    || (entity instanceof AbstractVehicle v && v.isDestroyed())) {
                continue;
            }
            if (irUsesNewLaunchData && irLaunchWeapon != null
                    && !RVP_IrLockHelper.isTargetWithinAcquireLimits(
                    weaponUnit, entity, irLaunchWeapon, scanDir)) {
                continue;
            }
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

    private RVP_IrHudProfile resolveIrHudProfile() {
        if (irHudProfile != RVP_IrHudProfile.MIXED || irLaunchWeapon == null) {
            return irHudProfile;
        }
        Entity locked = getLockedEntity();
        return locked != null && locked.isAlive()
                ? RVP_IrHudProfile.resolveForTarget(
                RVP_IrLockHelper.getLaunchAltitudeRange(irLaunchWeapon), locked)
                : RVP_IrHudProfile.MIXED;
    }

    private static double angleBetweenDeg(Vec3 a, Vec3 b) {
        if (a.lengthSqr() <= 1.0E-6 || b.lengthSqr() <= 1.0E-6) {
            return 0.0;
        }
        return Math.toDegrees(Math.acos(Mth.clamp(a.normalize().dot(b.normalize()), -1.0, 1.0)));
    }

    private static Vec3 clampDirectionToCone(Vec3 baseDir, Vec3 targetDir, float maxAngleDeg) {
        if (baseDir.lengthSqr() <= 1.0E-6) {
            return targetDir.lengthSqr() <= 1.0E-6 ? Vec3.ZERO : targetDir.normalize();
        }
        Vec3 base = baseDir.normalize();
        if (targetDir.lengthSqr() <= 1.0E-6) {
            return base;
        }
        Vec3 target = targetDir.normalize();
        double currentAngle = angleBetweenDeg(base, target);
        if (currentAngle <= maxAngleDeg) {
            return target;
        }
        Vec3 lateral = target.subtract(base.scale(base.dot(target)));
        if (lateral.lengthSqr() <= 1.0E-6) {
            return base;
        }
        Vec3 tangent = lateral.normalize();
        double maxAngleRad = Math.toRadians(maxAngleDeg);
        return base.scale(Math.cos(maxAngleRad)).add(tangent.scale(Math.sin(maxAngleRad))).normalize();
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
