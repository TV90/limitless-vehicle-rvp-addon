package org.ywzj.rvp.client.seeker;



import net.minecraft.client.Minecraft;

import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;

import org.ywzj.rvp.client.state.RVP_ClientSaclosGuidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import org.ywzj.rvp.weapon.core.RVP_WeaponBase;

import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import org.ywzj.rvp.ext.WeaponUnitSeekerExt;

import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;

import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import org.ywzj.vehicle.vehicle.part.WeaponUnit;



/**

 * RVP IR / SARH seeker: auto power, lock progress, target cycling.

 *

 * <p>Pre-launch locks use {@link WeaponUnit#getLockedEntity()}. In-flight HUD for deferred stages

 * (demo laser→SARH) uses separate client state so it cannot poison cockpit IR/SARH weapons.</p>

 */

public final class RVP_ClientSeekerController {



    private static String activeWeaponId = "";

    private static RVP_EnumGuidanceType activeMode = RVP_EnumGuidanceType.NONE;

    private static int activeInFlightMissileId = -1;

    private static boolean inFlightMode;



    private static Entity cockpitPendingTarget;

    private static int cockpitLockProgressTick;

    private static boolean cockpitManualTarget;



    private static Entity inFlightLocked;

    private static Entity inFlightPending;

    private static int inFlightLockProgressTick;

    private static boolean inFlightManualTarget;



    private RVP_ClientSeekerController() {}



    public static boolean isActive() {

        return currentContext() != null;

    }



    /** @deprecated use {@link RVP_SeekerVanillaHudCompat} for granular vanilla HUD suppression */
    public static boolean shouldSuppressVanillaHud() {
        return false;
    }



    public static float lockProgress() {

        SeekerContext ctx = currentContext();

        if (ctx == null) {

            return 0f;

        }

        int progress = ctx.inFlight() ? inFlightLockProgressTick : cockpitLockProgressTick;

        if (progress <= 0) {

            return 0f;

        }

        return Math.min(1f, progress / (float) RVP_SeekerWeaponUtil.seekerLockAcquireTicks(ctx.data()));

    }



    public static boolean isLocked() {

        SeekerContext ctx = currentContext();

        if (ctx == null) {

            return false;

        }

        return ctx.inFlight() ? inFlightLocked != null : ctx.unit().getLockedEntity() != null;

    }



    public static Entity displayTarget() {

        SeekerContext ctx = currentContext();

        if (ctx == null) {

            return null;

        }

        if (ctx.inFlight()) {

            return inFlightLocked != null ? inFlightLocked : inFlightPending;

        }

        Entity locked = ctx.unit().getLockedEntity();

        return locked != null ? locked : cockpitPendingTarget;

    }



    public static RVP_WeaponData activeWeaponData() {

        SeekerContext ctx = currentContext();

        return ctx != null ? ctx.data() : null;

    }



    public static void onWeaponSelected(WeaponUnit unit, RVP_WeaponData data) {

        if (!RVP_SeekerWeaponUtil.preLaunchSeekerHudActive(data)) {

            deactivate(unit);

            return;

        }

        clearCockpitLock(unit);

        activeWeaponId = weaponKey(data);

        activeMode = RVP_SeekerWeaponUtil.seekerMode(data);

        activeInFlightMissileId = -1;

        inFlightMode = false;

        cockpitPendingTarget = null;

        cockpitLockProgressTick = 0;

        cockpitManualTarget = false;

        unit.toggleSeeker(true);

    }



    public static void onWeaponDeselected(WeaponUnit unit) {

        deactivate(unit);

    }



    public static void onWeaponFired(WeaponUnit unit) {

        RVP_WeaponData data = resolveFiredWeaponData(unit);

        if (data == null) {

            return;

        }

        if (!RVP_SeekerWeaponUtil.preLaunchSeekerHudActive(data)) {

            clearCockpitLock(unit);

            return;

        }

        if (RVP_SeekerWeaponUtil.seekerRetainLockAfterFire(data)) {

            cockpitPendingTarget = null;

            cockpitLockProgressTick = 0;

            cockpitManualTarget = false;

            ensureSeekerPowered(unit);

            return;

        }

        deactivate(unit);

    }



    public static void tick() {

        SeekerContext ctx = currentContext();

        if (ctx == null) {

            if (activeMode != RVP_EnumGuidanceType.NONE) {

                WeaponUnit operator = LocalVehiclePlayer.instance.getWeaponUnit();

                deactivate(operator != null ? operator.getRootParentWeaponUnit() : null);

            }

            return;

        }



        if (!ctx.inFlight()) {

            ensureSeekerPowered(ctx.unit());

        }



        syncContextIdentity(ctx);



        double[] screenRef = seekerScreenReference(ctx.unit());

        if (ctx.inFlight()) {

            tickInFlight(ctx, screenRef[0], screenRef[1]);

        } else {

            tickCockpit(ctx, screenRef[0], screenRef[1]);

        }

    }



    public static void cycleTarget() {

        SeekerContext ctx = currentContext();

        if (ctx == null) {

            return;

        }

        if (!ctx.inFlight() && !ctx.unit().isSeekerOn()) {

            return;

        }

        double[] screenRef = seekerScreenReference(ctx.unit());

        Entity current = ctx.inFlight()

                ? (inFlightLocked != null ? inFlightLocked : inFlightPending)

                : firstNonNull(ctx.unit().getLockedEntity(), cockpitPendingTarget);



        Entity next = RVP_SeekerTargetScanner.cycleTarget(

                ctx.unit(), ctx.data(), activeMode, current, screenRef[0], screenRef[1]);

        if (next == null) {

            return;

        }



        if (ctx.inFlight()) {

            inFlightLocked = null;

            inFlightPending = next;

            inFlightLockProgressTick = 0;

            inFlightManualTarget = true;

        } else {

            clearWeaponLock(ctx.unit());

            cockpitPendingTarget = next;

            cockpitLockProgressTick = 0;

            cockpitManualTarget = true;

        }

        if (activeMode == RVP_EnumGuidanceType.SARH) {

            RVP_SeekerRadarSync.syncIllumination(ctx.unit(), next);

        }

    }



    private static void tickCockpit(SeekerContext ctx, double cx, double cy) {

        Entity locked = ctx.unit().getLockedEntity();

        if (locked != null) {

            if (!locked.isAlive() || !isStillValid(ctx, locked)) {

                clearCockpitLock(ctx.unit());

            } else {

                if (activeMode == RVP_EnumGuidanceType.SARH) {

                    RVP_SeekerRadarSync.syncIllumination(ctx.unit(), locked);

                }

                return;

            }

        }



        int coolingTicks = RVP_SeekerWeaponUtil.seekerLockCoolingTicks(ctx.data());

        if (ctx.unit().getLockCoolingTick() < coolingTicks) {

            cockpitPendingTarget = null;

            cockpitLockProgressTick = 0;

            return;

        }



        advanceLock(ctx, cx, cy, true);

    }



    private static void tickInFlight(SeekerContext ctx, double cx, double cy) {

        Entity locked = inFlightLocked;

        if (locked != null) {

            if (!locked.isAlive() || !isStillValid(ctx, locked)) {

                inFlightLocked = null;

                inFlightPending = null;

                inFlightLockProgressTick = 0;

                inFlightManualTarget = false;

                if (activeMode == RVP_EnumGuidanceType.SARH) {

                    RVP_SeekerRadarSync.syncIllumination(ctx.unit(), null);

                }

            } else {

                if (activeMode == RVP_EnumGuidanceType.SARH) {

                    RVP_SeekerRadarSync.syncIllumination(ctx.unit(), locked);

                }

                return;

            }

        }

        advanceLock(ctx, cx, cy, false);

    }



    private static void advanceLock(SeekerContext ctx, double cx, double cy, boolean cockpit) {

        Entity pending = cockpit ? cockpitPendingTarget : inFlightPending;

        boolean manual = cockpit ? cockpitManualTarget : inFlightManualTarget;



        if (pending == null || !pending.isAlive() || !isStillValid(ctx, pending)) {

            if (pending != null) {

                manual = false;

            }

            if (!manual) {

                pending = RVP_SeekerTargetScanner.pickAutoTarget(ctx.unit(), ctx.data(), activeMode, cx, cy);

            } else {

                pending = null;

            }

            if (cockpit) {

                cockpitPendingTarget = pending;

                cockpitLockProgressTick = 0;

                cockpitManualTarget = manual;

            } else {

                inFlightPending = pending;

                inFlightLockProgressTick = 0;

                inFlightManualTarget = manual;

            }

        }



        if (pending == null) {

            if (activeMode == RVP_EnumGuidanceType.SARH) {

                RVP_SeekerRadarSync.syncIllumination(ctx.unit(), null);

            }

            return;

        }



        if (activeMode == RVP_EnumGuidanceType.SARH) {

            RVP_SeekerRadarSync.syncIllumination(ctx.unit(), pending);

        }



        int progress = (cockpit ? cockpitLockProgressTick : inFlightLockProgressTick) + 1;

        if (cockpit) {

            cockpitLockProgressTick = progress;

        } else {

            inFlightLockProgressTick = progress;

        }



        int acquireTicks = RVP_SeekerWeaponUtil.seekerLockAcquireTicks(ctx.data());

        if (progress >= acquireTicks) {

            if (cockpit) {

                ctx.unit().setLockedEntity(pending);

                cockpitPendingTarget = null;

                cockpitLockProgressTick = 0;

                cockpitManualTarget = false;

            } else {

                inFlightLocked = pending;

                inFlightPending = null;

                inFlightLockProgressTick = 0;

                inFlightManualTarget = false;

            }

        }

    }



    private static void syncContextIdentity(SeekerContext ctx) {

        String weaponKey = weaponKey(ctx.data());

        int missileId = ctx.inFlightMissile() != null ? ctx.inFlightMissile().getId() : -1;

        boolean flight = ctx.inFlight();

        if (weaponKey.equals(activeWeaponId)

                && activeMode == RVP_SeekerWeaponUtil.seekerMode(ctx.data())

                && missileId == activeInFlightMissileId

                && flight == inFlightMode) {

            return;

        }

        activeWeaponId = weaponKey;

        activeMode = RVP_SeekerWeaponUtil.seekerMode(ctx.data());

        activeInFlightMissileId = missileId;

        inFlightMode = flight;

        cockpitPendingTarget = null;

        cockpitLockProgressTick = 0;

        cockpitManualTarget = false;

        inFlightLocked = null;

        inFlightPending = null;

        inFlightLockProgressTick = 0;

        inFlightManualTarget = false;

        if (!flight) {

            clearCockpitLock(ctx.unit());

        }

    }



    private static void deactivate(@Nullable WeaponUnit unit) {

        if (unit != null) {

            if (unit.getCurrentWeapon().map(w -> w.withSeeker()).orElse(false)) {

                unit.toggleSeeker(false);

            } else if (unit.isSeekerOn() && unit instanceof WeaponUnitSeekerExt seekerExt) {

                seekerExt.ywzj_rvp$forceSeekerOff();

            }

            clearCockpitLock(unit);

        }

        activeMode = RVP_EnumGuidanceType.NONE;

        activeWeaponId = "";

        activeInFlightMissileId = -1;

        inFlightMode = false;

        cockpitPendingTarget = null;

        cockpitLockProgressTick = 0;

        cockpitManualTarget = false;

        inFlightLocked = null;

        inFlightPending = null;

        inFlightLockProgressTick = 0;

        inFlightManualTarget = false;

    }



    private static void clearCockpitLock(WeaponUnit unit) {

        clearWeaponLock(unit);

        RVP_SeekerRadarSync.syncIllumination(unit, null);

    }



    private static void clearWeaponLock(WeaponUnit unit) {

        if (unit != null && unit.getLockedEntity() != null) {

            unit.setLockedEntity(null);

        }

    }



    private static void ensureSeekerPowered(WeaponUnit unit) {

        if (unit == null || unit.isSeekerOn()) {

            return;

        }

        if (unit instanceof WeaponUnitSeekerExt seekerExt) {

            seekerExt.ywzj_rvp$ensureSeekerOn();

        } else {

            unit.toggleSeeker(true);

        }

    }



    private static double[] seekerScreenReference(@Nullable WeaponUnit unit) {
        Minecraft mc = Minecraft.getInstance();
        double cx = mc.getWindow().getGuiScaledWidth() * 0.5;
        double cy = mc.getWindow().getGuiScaledHeight() * 0.5;
        WeaponUnit root = unit != null ? unit.getRootParentWeaponUnit() : null;
        return RVP_SeekerGeometry.screenReference(root, cx, cy);
    }

    private static boolean isStillValid(SeekerContext ctx, Entity entity) {

        if (activeMode == RVP_EnumGuidanceType.IR) {

            return RVP_SeekerTargetValidator.isValidIrTarget(ctx.unit(), entity, ctx.data());

        }

        if (activeMode == RVP_EnumGuidanceType.SARH) {
            double[] screenRef = seekerScreenReference(ctx.unit());
            return RVP_SeekerTargetScanner.listCandidates(
                    ctx.unit(), ctx.data(), activeMode, screenRef[0], screenRef[1]
            ).stream().anyMatch(c -> c.entity().getId() == entity.getId());
        }

        return false;

    }



    @Nullable

    private static SeekerContext currentContext() {

        WeaponUnit unit = LocalVehiclePlayer.instance.getWeaponUnit();

        if (unit == null) {

            return null;

        }

        WeaponUnit root = unit.getRootParentWeaponUnit();



        SeekerContext cockpit = unit.getCurrentWeapon()

                .filter(w -> w instanceof RVP_WeaponBase)

                .map(w -> (RVP_WeaponBase) w)

                .map(RVP_WeaponBase::getData)

                .filter(RVP_SeekerWeaponUtil::preLaunchSeekerHudActive)

                .map(data -> new SeekerContext(root, data, null, false))

                .orElse(null);

        if (cockpit != null) {

            return cockpit;

        }



        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null) {

            return null;

        }

        RVP_BaseBullet missile = RVP_ClientInFlightSeeker.findActiveMissile(mc.player, mc.level);

        if (missile == null) {

            return null;

        }

        RVP_WeaponData data = missile.getRvpData();

        if (data == null) {

            data = RVP_ClientSaclosGuidance.resolveWeaponData(missile);

        }

        if (data == null || !RVP_SeekerWeaponUtil.hasCockpitSeekerStage(data)) {

            return null;

        }

        return new SeekerContext(root, data, missile, true);

    }



    private static String weaponKey(RVP_WeaponData data) {

        return data.getWeaponId() != null ? data.getWeaponId().toString() : "";

    }



    @Nullable

    private static Entity firstNonNull(@Nullable Entity a, @Nullable Entity b) {

        return a != null ? a : b;

    }



    private static RVP_WeaponData resolveFiredWeaponData(WeaponUnit unit) {

        if (unit == null) {

            return activeWeaponData();

        }

        return unit.getCurrentWeapon()

                .filter(w -> w instanceof RVP_WeaponBase)

                .map(w -> ((RVP_WeaponBase) w).getData())

                .orElseGet(RVP_ClientSeekerController::activeWeaponData);

    }



    private record SeekerContext(

            WeaponUnit unit,

            RVP_WeaponData data,

            @Nullable RVP_BaseBullet inFlightMissile,

            boolean inFlight

    ) {}

}


