package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.mixin.GunnerWeaponAccessorMixin;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle.Seat;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Map;
import java.util.WeakHashMap;

public final class GunnerBrain {

    private GunnerBrain() {}
    private static final int AIR_PHASE_ATTACK = 0;
    private static final int AIR_PHASE_DISENGAGE = 1;
    private static final int GROUND_TACTICAL_HOLD_TICK = 100;
    private static final int GROUND_TACTICAL_EVADE_MIN_TICK = 140;
    private static final int GROUND_TACTICAL_EVADE_MAX_TICK = 280;
    private static final double FIXEDWING_ATTACK_ENTRY_MIN_AGL = 175.0;
    private static final double FIXEDWING_INITIAL_DISENGAGE_SCALE = 0.45;
    private static final double FIXEDWING_DISENGAGE_SCALE = 0.55;
    private static final double FIXEDWING_ATTACK_SCALE = 1.4;
    private static final double ROTARY_INITIAL_DISENGAGE_SCALE = 0.2;
    private static final double ROTARY_DISENGAGE_SCALE = 0.35;
    private static final double ROTARY_ATTACK_SCALE = 1.15;
    private static final Map<AbstractVehicleWeapon<?>, Long> SINGLE_SHOT_READY_TIME = new WeakHashMap<>();

    public static void tick(GunnerEntity gunner, AbstractVehicle vehicle) {
        gunner.tickCooldowns();
        GunnerProfile profile = GunnerProfileManager.INSTANCE.getProfile(
                GunnerProfileManager.INSTANCE.normalizeProfileId(gunner.getProfileId())
        );
        PartUnit<?> seatUnit = vehicle.getOwnOperatorUnit(gunner);
        boolean driver = isDriver(vehicle, gunner);
        WeaponUnit weaponUnit = resolveWeaponUnit(vehicle, seatUnit, driver);
        Entity target = tickTargeting(gunner, vehicle, weaponUnit, profile);

        tickCountermeasure(gunner, vehicle, profile);
        tickRadarLock(gunner, vehicle, weaponUnit, target, profile);

        boolean allowFire = true;
        if (driver && profile.isAllowDrive()) {
            refillDriverVehicle(gunner, vehicle);
            sustainDriverSingleShotWeapons(vehicle);
            allowFire = tickDriving(gunner, vehicle, target, profile);
        } else {
            clearDriverSingleShotWeaponTimers(vehicle);
            if (vehicle.getDriver() == gunner) {
                vehicle.controlUnit.reset();
            }
            gunner.clearDriverRideState();
        }

        if (weaponUnit != null && target != null && allowFire) {
            tickCombat(gunner, weaponUnit, target, profile);
        } else {
            gunner.setControlledWeaponIndex(-1);
        }
    }

    private static void tickRadarLock(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable WeaponUnit weaponUnit, @Nullable Entity target, GunnerProfile profile) {
        if (weaponUnit == null) {
            return;
        }
        if (profile.getFaction() != RVP_EnumGunnerFaction.ENEMY) {
            return;
        }
        if (weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF || weaponUnit.getMainRadarUnit() == null) {
            return;
        }

        Entity lockTarget = null;
        if (target instanceof Player player) {
            if (player.getVehicle() instanceof AbstractVehicle targetVehicle) {
                lockTarget = targetVehicle;
            } else {
                lockTarget = player;
            }
        } else if (target instanceof AbstractVehicle targetVehicle) {
            lockTarget = targetVehicle;
        }

        var radar = weaponUnit.getMainRadarUnit();
        if (lockTarget == null || !lockTarget.isAlive()) {
            if (radar.getLockedEntity() != null) {
                radar.setLockedEntity(null);
            }
            return;
        }

        double maxRange = radar.getMaxScanDistance();
        if (radar.worldRadarPosition().distanceToSqr(lockTarget.position()) > maxRange * maxRange) {
            if (radar.getLockedEntity() != null) {
                radar.setLockedEntity(null);
            }
            return;
        }

        Vec2 aimRot = radar.aimRot(lockTarget.position());
        if (aimRot.y < radar.getYRotMin() || aimRot.y > radar.getYRotMax()) {
            if (radar.getLockedEntity() != null) {
                radar.setLockedEntity(null);
            }
            return;
        }
        if (Math.abs(aimRot.x - radar.getXRot()) > radar.getScanSectorAngle() / 2.0f) {
            if (radar.getLockedEntity() != null) {
                radar.setLockedEntity(null);
            }
            return;
        }

        if (radar.getLockedEntity() != lockTarget) {
            radar.setLockedEntity(lockTarget);
        }
    }

    @Nullable
    private static Entity tickTargeting(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable WeaponUnit weaponUnit, GunnerProfile profile) {
        if (weaponUnit == null) {
            gunner.setTrackedTarget(null);
            return null;
        }
        if (gunner.tickCount % profile.getScanIntervalTick() == 0) {
            gunner.setTrackedTarget(GunnerTargeting.findBestTarget(gunner, vehicle, weaponUnit, profile));
        }
        Entity tracked = gunner.getTrackedTarget();
        if (tracked == null || !tracked.isAlive()) {
            gunner.setTrackedTarget(null);
            return null;
        }
        return tracked;
    }

    private static void tickCombat(GunnerEntity gunner, WeaponUnit weaponUnit, Entity target, GunnerProfile profile) {
        Vec3 aimPoint = GunnerTargeting.predictAimPoint(weaponUnit.worldPivotPosition(), target)
                .add(target.getDeltaMovement().scale(Math.max(0.0, profile.getLeadScale() - 1.0)));
        weaponUnit.aim(aimPoint);

        int weaponIndex = selectWeaponIndex(weaponUnit);
        if (weaponIndex < 0) {
            gunner.setControlledWeaponIndex(-1);
            return;
        }
        gunner.setControlledWeaponIndex(weaponIndex);

        if (!gunner.isBurstWindowOpen()) {
            return;
        }

        float xErr = Math.abs(Mth.wrapDegrees(weaponUnit.getXRot() - weaponUnit.getXAimRot()));
        float yErr = Math.abs(Mth.wrapDegrees(weaponUnit.getYRot() - weaponUnit.getYAimRot()));
        if (xErr <= profile.getFireWindowDeg() && yErr <= profile.getFireWindowDeg()) {
            weaponUnit.shoot(weaponIndex, weaponUnit.aimContexts(), gunner);
            gunner.onBurstShot(profile.getBurstFireTick(), profile.getBurstRestTick());
        }
    }

    private static boolean tickDriving(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        vehicle.controlUnit.reset();
        boolean allowFire = true;
        if (vehicle instanceof FixedWingVehicle fixedWingVehicle) {
            allowFire = tickFixedWingDriving(gunner, fixedWingVehicle, target, profile);
            return allowFire;
        }
        if (vehicle instanceof RotaryWingVehicle rotaryWingVehicle) {
            allowFire = tickRotaryDriving(gunner, rotaryWingVehicle, target, profile);
            return allowFire;
        }
        tickGroundDriving(gunner, vehicle, target, profile);
        return allowFire;
    }

    private static void tickGroundDriving(GunnerEntity gunner, AbstractVehicle vehicle, Entity target, GunnerProfile profile) {
        if (target == null) {
            gunner.clearTacticalEvade();
            tickGroundWander(gunner, vehicle, profile);
            return;
        }
        Vec3 delta = target.position().subtract(vehicle.position());
        double distSqr = delta.horizontalDistanceSqr();
        double dist = Math.sqrt(distSqr);
        Vec2 targetRot = VectorUtil.vecToRot(new Vec3(delta.x, 0, delta.z));
        float yawDelta = Mth.wrapDegrees(targetRot.y - vehicle.getYRot());

        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        boolean desireMove = dist > stopDist * 1.6 && Math.abs(yawDelta) < 25.0f;
        boolean tacticalTarget = stopDist > 0.0;

        if (tacticalTarget && dist <= stopDist) {
            if (!gunner.hasTacticalHoldTicks() && !gunner.hasTacticalEvadeTicks()) {
                float yawBias = gunner.getRandom().nextBoolean() ? 55.0F : -55.0F;
                gunner.startTacticalHold(GROUND_TACTICAL_HOLD_TICK);
                gunner.startTacticalEvade(GROUND_TACTICAL_EVADE_MIN_TICK
                        + gunner.getRandom().nextInt(GROUND_TACTICAL_EVADE_MAX_TICK - GROUND_TACTICAL_EVADE_MIN_TICK + 1), yawBias);
            }
        } else if (!tacticalTarget || dist > stopDist * 1.8) {
            gunner.clearTacticalEvade();
        }

        if (gunner.tickCount % profile.getDriveStuckCheckTick() == 0 && gunner.getRecoveryCooldownTicks() <= 0) {
            double moved = vehicle.position().distanceToSqr(gunner.getLastDriveCheckX(), vehicle.getY(), gunner.getLastDriveCheckZ());
            double stuckDist = profile.getDriveStuckDistance();
            if (desireMove && moved < stuckDist * stuckDist) {
                gunner.startRecovery(profile.getDriveRecoveryTick());
                gunner.setRecoveryCooldownTicks(profile.getDriveRecoveryTick() * 2 + profile.getDriveStuckCheckTick());
            }
            gunner.setLastDriveCheck(vehicle.getX(), vehicle.getZ());
        }

        if (gunner.hasRecoveryTicks()) {
            vehicle.controlUnit.backward = true;
            if (yawDelta > 0) {
                vehicle.controlUnit.right = true;
            } else {
                vehicle.controlUnit.left = true;
            }
            return;
        }

        if (gunner.hasTacticalHoldTicks()) {
            return;
        }

        if (gunner.hasTacticalEvadeTicks()) {
            tickGroundTacticalEvade(gunner, vehicle, target, yawDelta);
            return;
        }

        if (yawDelta > 8) {
            vehicle.controlUnit.right = true;
        } else if (yawDelta < -8) {
            vehicle.controlUnit.left = true;
        }

        if (dist > stopDist * 1.2 && Math.abs(yawDelta) < 80) {
            vehicle.controlUnit.forward = true;
        }
    }

    private static void tickGroundTacticalEvade(GunnerEntity gunner, AbstractVehicle vehicle, Entity target, float targetYawDelta) {
        Vec3 away = vehicle.position().subtract(target.position());
        if (away.horizontalDistanceSqr() < 1.0E-4) {
            away = vehicle.getLookAngle();
        }
        Vec2 awayRot = VectorUtil.vecToRot(new Vec3(away.x, 0, away.z));
        float desiredYaw = awayRot.y + gunner.getTacticalEvadeYawBias();
        float evadeYawDelta = Mth.wrapDegrees(desiredYaw - vehicle.getYRot());

        if (evadeYawDelta > 8.0F) {
            vehicle.controlUnit.right = true;
        } else if (evadeYawDelta < -8.0F) {
            vehicle.controlUnit.left = true;
        }

        if (Math.abs(evadeYawDelta) < 100.0F) {
            vehicle.controlUnit.forward = true;
        } else {
            vehicle.controlUnit.backward = true;
        }

        if (Math.abs(targetYawDelta) > 60.0F) {
            if (targetYawDelta > 0.0F) {
                vehicle.controlUnit.right = true;
            } else {
                vehicle.controlUnit.left = true;
            }
        }
    }

    private static boolean tickFixedWingDriving(GunnerEntity gunner, FixedWingVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        if (target == null) {
            tickFixedWingCruise(gunner, vehicle, profile, false);
            return false;
        }
        boolean allowFire = ensureAirPhase(gunner, vehicle, profile);
        boolean attackPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK;
        tickFixedWingCruise(gunner, vehicle, profile, attackPhase);

        Vec3 delta = target.position().subtract(vehicle.position());
        double horizontalDist = new Vec3(delta.x, 0, delta.z).length();
        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        boolean breakAway = horizontalDist < Math.max(stopDist * 4.0, 48.0);

        Vec3 aimPoint;
        if (!attackPhase || breakAway) {
            Vec3 forward = vehicle.getLookAngle().normalize();
            aimPoint = vehicle.position().add(forward.scale(128)).add(0, 30, 0);
        } else {
            aimPoint = target.position().add(target.getDeltaMovement().scale(10)).add(0, 12, 0);
        }

        Vec3 homePos = gunner.getHomePos();
        if (homePos != null) {
            double d = vehicle.position().distanceTo(homePos);
            double min = profile.getFixedwingCombatRadiusMin();
            double max = profile.getFixedwingCombatRadiusMax();
            if (max > 0.0 && max >= min) {
                Vec3 biasDir = null;
                double strength = 0.0;
                if (d < min) {
                    strength = (min - d) / Math.max(min, 1.0);
                    Vec3 out = vehicle.position().subtract(homePos);
                    if (out.lengthSqr() < 1.0E-4) {
                        out = vehicle.getLookAngle();
                    }
                    biasDir = out.normalize();
                } else if (d > max) {
                    strength = (d - max) / Math.max(max, 1.0);
                    biasDir = homePos.subtract(vehicle.position()).normalize();
                }
                if (biasDir != null && strength > 0.0) {
                    double offset = 220.0 * Math.min(1.0, strength);
                    if (d > max * 1.5) {
                        offset += 260.0 * Math.min(1.0, (d - max * 1.5) / Math.max(max, 1.0));
                    }
                    aimPoint = aimPoint.add(biasDir.scale(offset));
                }
            }
        }

        Vec2 desiredRot = VectorUtil.vecToRot(aimPoint.subtract(vehicle.position()));
        vehicle.controlUnit.yRot = desiredRot.y;
        vehicle.controlUnit.yRotKeep = false;

        vehicle.controlUnit.forward = true;

        if (vehicle.onGround()) {
            float groundYawDelta = Mth.wrapDegrees(desiredRot.y - vehicle.getYRot());
            if (groundYawDelta > 6) {
                vehicle.controlUnit.rightYaw = true;
            } else if (groundYawDelta < -6) {
                vehicle.controlUnit.leftYaw = true;
            }
        }
        return allowFire;
    }

    private static boolean tickRotaryDriving(GunnerEntity gunner, RotaryWingVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        if (target == null) {
            tickRotaryCruise(gunner, vehicle, profile, false);
            return false;
        }
        boolean allowFire = ensureRotaryAirPhase(gunner, profile);
        boolean attackPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK;
        tickRotaryCruise(gunner, vehicle, profile, attackPhase);

        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        double currentAgl = vehicle.getY() - groundY;
        double takeoffAgl = Math.min(profile.getRotaryCruiseAltitudeMin(), 25.0);
        if (currentAgl < takeoffAgl) {
            vehicle.hoverMode = true;
            vehicle.controlUnit.up = true;
            vehicle.controlUnit.yRotKeep = true;
            vehicle.controlUnit.xRotKeep = true;
            return false;
        }

        vehicle.hoverMode = false;
        Vec3 delta = target.position().subtract(vehicle.position());
        double horizontalDist = new Vec3(delta.x, 0, delta.z).length();
        Vec3 facingVec = attackPhase ? delta : vehicle.position().subtract(target.position());
        if (facingVec.horizontalDistanceSqr() < 1.0E-4) {
            facingVec = vehicle.getLookAngle();
        }
        Vec2 facingRot = VectorUtil.vecToRot(facingVec);
        Vec2 targetRot = VectorUtil.vecToRot(delta);
        vehicle.controlUnit.yRot = facingRot.y;
        vehicle.controlUnit.yRotKeep = false;
        vehicle.controlUnit.xRotKeep = false;

        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        float desiredPitch = computeRotaryPitch(vehicle, profile, attackPhase, currentAgl, horizontalDist, stopDist, targetRot);
        vehicle.controlUnit.xRot = desiredPitch;
        return allowFire;
    }

    private static double pickCruiseAgl(double currentAgl, double minAgl, double maxAgl) {
        double min = Math.max(0.0, minAgl);
        double max = Math.max(min, maxAgl);
        double mid = (min + max) * 0.5;
        double deadBand = Math.max(5.0, (max - min) * 0.08);
        if (currentAgl < min) {
            return min;
        }
        if (currentAgl > max) {
            return max;
        }
        if (Math.abs(currentAgl - mid) <= deadBand) {
            return currentAgl;
        }
        return mid;
    }

    private static void tickFixedWingCruise(GunnerEntity gunner, FixedWingVehicle vehicle, GunnerProfile profile, boolean attackPhase) {
        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        double currentAgl = vehicle.getY() - groundY;
        double min = profile.getFixedwingCruiseAltitudeMin();
        double max = profile.getFixedwingCruiseAltitudeMax();
        double desiredAgl;
        if (attackPhase) {
            desiredAgl = Mth.clamp(175.0, min, max);
        } else {
            double span = Math.max(max - min, 0.0);
            double lowCruise = min + span * 0.2;
            double highCruise = min + span * 0.8;
            // Give each gunner a slow, per-entity altitude wave so fixed-wing AI does not hug max altitude forever.
            double wave = (Math.sin((gunner.tickCount + gunner.getId() * 37.0) * 0.0125) + 1.0) * 0.5;
            desiredAgl = Mth.lerp(wave, lowCruise, highCruise);
        }
        if (currentAgl < min) {
            desiredAgl = min;
        } else if (currentAgl > max) {
            desiredAgl = max;
        }
        double desiredAlt = groundY + desiredAgl;
        double altErr = desiredAlt - vehicle.getY();
        float pitchCmd = (float) Mth.clamp(-altErr * 0.25, -18.0, 10.0);
        if (!attackPhase) {
            pitchCmd = (float) Mth.clamp(pitchCmd - 4.0F, -18.0, 10.0);
        }
        if (vehicle.onGround() && vehicle.getY() < 68) {
            pitchCmd = -10.0F;
        }
        vehicle.controlUnit.forward = true;
        vehicle.controlUnit.xRot = pitchCmd;
        vehicle.controlUnit.xRotKeep = false;
    }

    private static void tickRotaryCruise(GunnerEntity gunner, RotaryWingVehicle vehicle, GunnerProfile profile, boolean attackPhase) {
        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        double currentAgl = vehicle.getY() - groundY;
        double min = profile.getRotaryCruiseAltitudeMin();
        double max = profile.getRotaryCruiseAltitudeMax();
        double desiredAgl = attackPhase ? (min + max) * 0.5 : max;
        if (currentAgl < min) {
            desiredAgl = min;
        } else if (currentAgl > max) {
            desiredAgl = max;
        }
        double desiredAlt = groundY + desiredAgl;
        double altitudeError = desiredAlt - vehicle.getY();
        if (vehicle.getCollectivePitch() < 55.0f) {
            vehicle.controlUnit.up = true;
        } else if (altitudeError > 2.0) {
            vehicle.controlUnit.up = true;
        } else if (altitudeError < -2.0) {
            vehicle.controlUnit.down = true;
        }
    }

    private static void tickGroundWander(GunnerEntity gunner, AbstractVehicle vehicle, GunnerProfile profile) {
        if (!profile.isGroundWanderEnabled()) {
            return;
        }
        int cooldown = gunner.getGroundBigTurnCooldown();
        int turning = gunner.getGroundBigTurnTicks();
        if (turning > 0) {
            gunner.setGroundBigTurnTicks(turning - 1);
            float yawDelta = Mth.wrapDegrees(gunner.getGroundBigTurnTargetYaw() - vehicle.getYRot());
            if (Math.abs(yawDelta) > 6) {
                if (yawDelta > 0) {
                    vehicle.controlUnit.right = true;
                } else {
                    vehicle.controlUnit.left = true;
                }
            } else {
                gunner.setGroundBigTurnTicks(0);
            }
            return;
        }
        if (cooldown > 0) {
            gunner.setGroundBigTurnCooldown(cooldown - 1);
        } else {
            int minTick = profile.getGroundBigTurnIntervalTickMin();
            int maxTick = profile.getGroundBigTurnIntervalTickMax();
            int next = minTick + gunner.getRandom().nextInt(Math.max(1, maxTick - minTick + 1));
            gunner.setGroundBigTurnCooldown(next);

            float minDeg = profile.getGroundBigTurnAngleDegMin();
            float maxDeg = profile.getGroundBigTurnAngleDegMax();
            float ang = minDeg + gunner.getRandom().nextFloat() * Math.max(0.0F, maxDeg - minDeg);
            if (gunner.getRandom().nextBoolean()) {
                ang = -ang;
            }
            gunner.setGroundBigTurnTargetYaw(vehicle.getYRot() + ang);
            gunner.setGroundBigTurnTicks(profile.getGroundBigTurnDurationTick());
            return;
        }

        vehicle.controlUnit.forward = true;
        if ((gunner.tickCount / 40) % 2 == 0) {
            vehicle.controlUnit.left = true;
        }
    }

    private static boolean ensureAirPhase(GunnerEntity gunner, FixedWingVehicle vehicle, GunnerProfile profile) {
        if (!gunner.isAirPhaseInitialized()) {
            gunner.setAirPhaseInitialized(true);
            gunner.setAirPhase(AIR_PHASE_DISENGAGE);
            int initial = pickScaledTickRange(gunner,
                    profile.getAirInitialDisengageTickMin(),
                    profile.getAirInitialDisengageTickMax(),
                    FIXEDWING_INITIAL_DISENGAGE_SCALE,
                    40,
                    180);
            gunner.setAirPhaseTicks(initial);
            return false;
        }
        int ticks = gunner.getAirPhaseTicks();
        if (ticks <= 0) {
            int nextPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK ? AIR_PHASE_DISENGAGE : AIR_PHASE_ATTACK;
            if (nextPhase == AIR_PHASE_ATTACK) {
                double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
                double currentAgl = vehicle.getY() - groundY;
                if (currentAgl < FIXEDWING_ATTACK_ENTRY_MIN_AGL) {
                    gunner.setAirPhase(AIR_PHASE_DISENGAGE);
                    gunner.setAirPhaseTicks(20);
                    return false;
                }
            }
            gunner.setAirPhase(nextPhase);
            int nextTicks = nextPhase == AIR_PHASE_ATTACK
                    ? scaleAirTicks(profile.getAirAttackPhaseTick(), FIXEDWING_ATTACK_SCALE, 140, 420)
                    : scaleAirTicks(profile.getAirDisengagePhaseTick(), FIXEDWING_DISENGAGE_SCALE, 40, 140);
            gunner.setAirPhaseTicks(nextTicks);
        } else {
            gunner.setAirPhaseTicks(ticks - 1);
        }
        return gunner.getAirPhase() == AIR_PHASE_ATTACK;
    }

    private static boolean ensureRotaryAirPhase(GunnerEntity gunner, GunnerProfile profile) {
        if (!gunner.isAirPhaseInitialized()) {
            gunner.setAirPhaseInitialized(true);
            gunner.setAirPhase(AIR_PHASE_DISENGAGE);
            int initial = pickScaledTickRange(gunner,
                    profile.getAirInitialDisengageTickMin(),
                    profile.getAirInitialDisengageTickMax(),
                    ROTARY_INITIAL_DISENGAGE_SCALE,
                    20,
                    90);
            gunner.setAirPhaseTicks(initial);
            return false;
        }

        int ticks = gunner.getAirPhaseTicks();
        if (ticks <= 0) {
            int nextPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK ? AIR_PHASE_DISENGAGE : AIR_PHASE_ATTACK;
            gunner.setAirPhase(nextPhase);
            int nextTicks = nextPhase == AIR_PHASE_ATTACK
                    ? scaleAirTicks(profile.getAirAttackPhaseTick(), ROTARY_ATTACK_SCALE, 120, 320)
                    : scaleAirTicks(profile.getAirDisengagePhaseTick(), ROTARY_DISENGAGE_SCALE, 40, 120);
            gunner.setAirPhaseTicks(nextTicks);
        } else {
            gunner.setAirPhaseTicks(ticks - 1);
        }
        return gunner.getAirPhase() == AIR_PHASE_ATTACK;
    }

    private static int pickScaledTickRange(GunnerEntity gunner, int min, int max, double scale, int floor, int ceil) {
        int scaledMin = scaleAirTicks(min, scale, floor, ceil);
        int scaledMax = scaleAirTicks(max, scale, floor, ceil);
        return scaledMin + gunner.getRandom().nextInt(Math.max(1, scaledMax - scaledMin + 1));
    }

    private static int scaleAirTicks(int value, double scale, int floor, int ceil) {
        int scaled = (int) Math.round(value * scale);
        return Mth.clamp(scaled, floor, ceil);
    }

    private static float computeRotaryPitch(RotaryWingVehicle vehicle, GunnerProfile profile, boolean attackPhase,
                                            double currentAgl, double horizontalDist, double stopDist, Vec2 targetRot) {
        float desiredPitch = Mth.clamp(targetRot.x * 0.75F, -8.0F, 10.0F);
        double farDist = Math.max(stopDist * 2.0, 28.0);
        double nearDist = Math.max(stopDist * 0.9, 12.0);

        if (attackPhase) {
            if (horizontalDist > farDist) {
                desiredPitch = Mth.clamp(desiredPitch + 2.5F, -8.0F, 11.0F);
            } else if (horizontalDist < nearDist) {
                desiredPitch = Mth.clamp(desiredPitch - 4.0F, -10.0F, 8.0F);
            }
        } else {
            desiredPitch = Mth.clamp(desiredPitch - 1.5F, -8.0F, 7.0F);
        }

        double descentRate = vehicle.getDeltaMovement().y;
        double lowAgl = Math.max(14.0, profile.getRotaryCruiseAltitudeMin() * 0.45);
        double hardLowAgl = Math.max(8.0, profile.getRotaryCruiseAltitudeMin() * 0.3);
        if (currentAgl < lowAgl || descentRate < -0.18) {
            desiredPitch = Math.min(desiredPitch, -4.0F);
            vehicle.controlUnit.up = true;
        }
        if (currentAgl < hardLowAgl || descentRate < -0.35) {
            desiredPitch = Math.min(desiredPitch, -8.0F);
            vehicle.controlUnit.up = true;
        }
        return desiredPitch;
    }

    private static void tickCountermeasure(GunnerEntity gunner, AbstractVehicle vehicle, GunnerProfile profile) {
        if (gunner.getCountermeasureCooldown() > 0) {
            return;
        }
        AmmoEntity threat = GunnerTargeting.findAmmoThreat(gunner, vehicle, profile.getCountermeasureRange());
        if (threat == null) {
            return;
        }

        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
                AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
                if (isCountermeasureWeapon(weapon) && weapon.hasAmmo() && !weapon.isCoolingDown() && !weapon.isReloading()) {
                    weaponUnit.aim(threat.position());
                    weaponUnit.shoot(index, weaponUnit.aimContexts(), gunner);
                    gunner.setCountermeasureCooldown(profile.getCountermeasureCooldownTick());
                    return;
                }
            }
        }
    }

    private static void refillDriverVehicle(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (!gunner.markDriverRide(vehicle.getId())) {
            return;
        }

        if (!gunner.hasHomePos()) {
            gunner.setHomePos(vehicle.position());
        }
        vehicle.toggleEngine(true);
        vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                    weapon.setRemainAmmo(weapon.getMaxCapacity());
                    ((GunnerWeaponAccessorMixin) (Object) weapon).ywzj_rvp$setReloadTime(0);
                }
            }
        }
    }

    private static void sustainDriverSingleShotWeapons(AbstractVehicle vehicle) {
        long now = System.currentTimeMillis();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                if (weapon.getMaxCapacity() > 1) {
                    SINGLE_SHOT_READY_TIME.remove(weapon);
                    continue;
                }

                Long readyTime = SINGLE_SHOT_READY_TIME.get(weapon);
                if (weapon.getRemainAmmo() <= 0 && readyTime == null) {
                    readyTime = now + getSingleShotDriverIntervalMs(weapon);
                    SINGLE_SHOT_READY_TIME.put(weapon, readyTime);
                }

                if (readyTime == null) {
                    ((GunnerWeaponAccessorMixin) (Object) weapon).ywzj_rvp$setReloadTime(0);
                    continue;
                }

                long remainMs = Math.max(0L, readyTime - now);
                if (remainMs > 0L) {
                    ((GunnerWeaponAccessorMixin) (Object) weapon).ywzj_rvp$setReloadTime(msToTicks(remainMs));
                    continue;
                }

                weapon.setRemainAmmo(Math.max(1, weapon.getMaxCapacity()));
                ((GunnerWeaponAccessorMixin) (Object) weapon).ywzj_rvp$setReloadTime(0);
                SINGLE_SHOT_READY_TIME.remove(weapon);
            }
        }
    }

    private static void clearDriverSingleShotWeaponTimers(AbstractVehicle vehicle) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                SINGLE_SHOT_READY_TIME.remove(weapon);
                ((GunnerWeaponAccessorMixin) (Object) weapon).ywzj_rvp$setReloadTime(0);
            }
        }
    }

    private static long getSingleShotDriverIntervalMs(AbstractVehicleWeapon<?> weapon) {
        long reloadMs = Math.max(0, weapon.getData().getReload().getTime()) * 50L;
        long cooldownMs = Math.max(0L, weapon.getShootInterval());
        return reloadMs + cooldownMs;
    }

    private static int msToTicks(long ms) {
        return Math.max(1, (int) ((ms + 49L) / 50L));
    }

    private static int selectWeaponIndex(WeaponUnit weaponUnit) {
        for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
            AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
            if (weapon.hasAmmo() && !weapon.isCoolingDown() && !weapon.isReloading() && !isCountermeasureWeapon(weapon)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isCountermeasureWeapon(AbstractVehicleWeapon<?> weapon) {
        ResourceLocation weaponId = weapon.getData().getWeaponId();
        if (weaponId == null) {
            return false;
        }
        String path = weaponId.getPath();
        return path.contains("decoy_flare") || path.contains("smoke_grenade") || path.contains("aps_grenade");
    }

    private static boolean isDriver(AbstractVehicle vehicle, GunnerEntity gunner) {
        if (vehicle.getDriver() == gunner) {
            return true;
        }
        for (Seat seat : vehicle.seats) {
            if (seat.passengerId == gunner.getId()) {
                return seat.seatIndex == 0;
            }
        }
        return false;
    }

    @Nullable
    private static WeaponUnit resolveWeaponUnit(AbstractVehicle vehicle, @Nullable PartUnit<?> seatUnit, boolean driver) {
        if (seatUnit instanceof WeaponUnit weaponUnit) {
            return weaponUnit;
        }
        if (!driver) {
            return null;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit && !weaponUnit.getIndexedWeapons().isEmpty()) {
                return weaponUnit;
            }
        }
        return null;
    }
}
