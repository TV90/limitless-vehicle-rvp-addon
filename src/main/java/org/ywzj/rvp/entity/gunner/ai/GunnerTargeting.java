package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerFaction;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.weapon.AerialBombEntity;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GunnerTargeting {

    private GunnerTargeting() {}

    @Nullable
    public static Entity findBestTarget(GunnerEntity gunner, AbstractVehicle vehicle, WeaponUnit weaponUnit, GunnerProfile profile) {
        double radius = getTargetSearchRadius(vehicle, profile);
        AABB box = vehicle.getBoundingBox().inflate(radius);
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        List<Entity> entities = vehicle.level().getEntities(vehicle, box, entity -> isValidTarget(gunner, vehicle, vehicleTeam, gunnerTeam, entity, profile));
        List<Entity> hostileGunnerVehicles = entities.stream()
                .filter(entity -> isRelativeHostileGunnerVehicle(gunner, entity))
                .toList();
        List<Entity> preferred = hostileGunnerVehicles.isEmpty() ? entities : hostileGunnerVehicles;
        return preferred.stream()
                .min(Comparator.comparingDouble(entity -> score(vehicle, weaponUnit, entity)))
                .orElse(null);
    }

    private static double getTargetSearchRadius(AbstractVehicle vehicle, GunnerProfile profile) {
        double base = profile.getSearchRadius();
        if (vehicle instanceof org.ywzj.vehicle.entity.vehicle.FixedWingVehicle
                || vehicle instanceof org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle) {
            return base * 2.0;
        }
        return base;
    }

    @Nullable
    public static AmmoEntity findAmmoThreat(GunnerEntity gunner, AbstractVehicle vehicle, double radius) {
        AABB box = vehicle.getBoundingBox().inflate(radius);
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        List<Entity> entities = vehicle.level().getEntities(vehicle, box, entity -> entity instanceof AmmoEntity ammo
                && ammo.isAlive()
                && ammo.vehicle != vehicle
                && !vehicle.getPassengers().contains(ammo.getOwner())
                && !(ammo.getOwner() != null && gunner.isOwnedBy(ammo.getOwner()))
                && !isAllied(ammo.getOwner(), vehicleTeam)
                && !isAllied(ammo.getOwner(), gunnerTeam));
        Vec3 vehiclePos = vehicle.position();
        AmmoEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Entity entity : entities) {
            AmmoEntity ammo = (AmmoEntity) entity;
            if (!isDangerousAmmo(ammo)) {
                continue;
            }
            if (ammo.vehicle == null) {
                continue;
            }
            Vec3 ammoVel = ammo.getDeltaMovement();
            if (ammoVel.lengthSqr() < 0.04) {
                continue;
            }
            Vec3 toVehicle = vehiclePos.subtract(ammo.position());
            double distSqr = toVehicle.lengthSqr();
            if (distSqr <= 1.0 || distSqr >= bestDistSqr) {
                continue;
            }
            double dist = Math.sqrt(distSqr);
            if (dist > Math.min(radius, 32.0)) {
                continue;
            }
            Vec3 toVehicleDir = toVehicle.normalize();
            double closing = ammoVel.dot(toVehicleDir);
            if (closing <= 0.35) {
                continue;
            }
            double timeToImpactTick = dist / closing * 20.0;
            if (timeToImpactTick > 25.0) {
                continue;
            }
            best = ammo;
            bestDistSqr = distSqr;
        }
        return best;
    }

    private static boolean isDangerousAmmo(AmmoEntity ammo) {
        if (ammo instanceof MissileEntity) {
            return true;
        }
        if (ammo instanceof AerialBombEntity) {
            return true;
        }
        if (ammo.getWeaponId() == null) {
            return false;
        }
        String path = ammo.getWeaponId().getPath().toLowerCase(Locale.ROOT);
        return path.contains("missile") || path.contains("bomb");
    }

    public static Vec3 predictAimPoint(Vec3 sourcePos, Entity target) {
        Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
        double leadScale = Mth.clamp(sourcePos.distanceToSqr(center) / 256.0, 0.5, 4.0);
        return center.add(target.getDeltaMovement().scale(leadScale));
    }

    private static boolean isValidTarget(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable Team vehicleTeam, @Nullable Team gunnerTeam, Entity entity, GunnerProfile profile) {
        if (!entity.isAlive() || entity == gunner || entity == vehicle) {
            return false;
        }
        if (vehicle.getPassengers().contains(entity)) {
            return false;
        }
        if (entity instanceof Player player) {
            if (player.isSpectator()) {
                return false;
            }
            if (player.isCreative() && vehicle.level().getDifficulty() != Difficulty.HARD) {
                return false;
            }
        }
        TargetMatch match = matchProfileTarget(gunner, vehicle, entity, profile);
        if (!match.allowed) {
            return false;
        }
        if (gunner.isOwnedBy(entity)) {
            return false;
        }
        if (shouldApplyTeamFilter(entity, profile.getFaction())
                && !match.bypassTeamFilter
                && (isAllied(entity, vehicleTeam) || isAllied(entity, gunnerTeam))) {
            return false;
        }
        return true;
    }

    private static boolean shouldApplyTeamFilter(Entity entity, GunnerFaction faction) {
        if (faction == GunnerFaction.ENEMY) {
            return false;
        }
        return true;
    }

    private static boolean isAllied(@Nullable Entity entity, @Nullable Team alliedTeam) {
        if (entity == null) {
            return false;
        }
        Team targetTeam = entity.getTeam();
        return alliedTeam != null && targetTeam != null && targetTeam.isAlliedTo(alliedTeam);
    }

    private static TargetMatch matchProfileTarget(GunnerEntity gunner, AbstractVehicle vehicle, Entity entity, GunnerProfile profile) {
        List<String> targetTypes = profile.getTargetTypes();
        if (targetTypes == null || targetTypes.isEmpty()) {
            return TargetMatch.DISALLOWED;
        }

        for (String raw : targetTypes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String type = raw.toLowerCase(Locale.ROOT).trim();
            if ("monster".equals(type) && isMonster(entity)) {
                return TargetMatch.allowed(false);
            }
            if ("player".equals(type) && entity instanceof Player) {
                return TargetMatch.allowed(false);
            }
            if ("neutral".equals(type) && isNeutralLiving(entity)) {
                return TargetMatch.allowed(false);
            }
            if ("living".equals(type) && entity instanceof LivingEntity) {
                return TargetMatch.allowed(false);
            }
            if ("vehicle".equals(type) && entity instanceof AbstractVehicle) {
                return isOccupiedVehicle(entity) ? TargetMatch.allowed(false) : TargetMatch.DISALLOWED;
            }
            if ("vehicle:enemy_gunner".equals(type) && isVehicleDrivenByFaction(entity, GunnerFaction.ENEMY)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:friendly_gunner".equals(type) && isVehicleDrivenByFaction(entity, GunnerFaction.FRIENDLY)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:team_gunner".equals(type) && isVehicleDrivenByFaction(entity, GunnerFaction.TEAM)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:player".equals(type) && isVehicleDrivenByPlayer(entity)) {
                return TargetMatch.allowed(false);
            }
            if ("vehicle:non_allied_gunner".equals(type) && isVehicleDrivenByNonAlliedGunner(entity, gunner.getTeam())) {
                return TargetMatch.allowed(false);
            }
        }
        return TargetMatch.DISALLOWED;
    }

    private static final class TargetMatch {
        private static final TargetMatch DISALLOWED = new TargetMatch(false, false);
        private final boolean allowed;
        private final boolean bypassTeamFilter;

        private TargetMatch(boolean allowed, boolean bypassTeamFilter) {
            this.allowed = allowed;
            this.bypassTeamFilter = bypassTeamFilter;
        }

        private static TargetMatch allowed(boolean bypassTeamFilter) {
            return new TargetMatch(true, bypassTeamFilter);
        }
    }

    private static boolean isMonster(Entity entity) {
        return entity.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
                || entity instanceof net.minecraft.world.entity.monster.Monster;
    }

    private static boolean isNeutralLiving(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        if (entity instanceof Player) {
            return false;
        }
        if (entity instanceof AbstractVehicle) {
            return false;
        }
        if (entity instanceof GunnerEntity) {
            return false;
        }
        return !isMonster(entity);
    }

    private static boolean isVehicleDrivenByFaction(Entity entity, GunnerFaction faction) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        Entity driver = vehicle.getDriver();
        if (!(driver instanceof GunnerEntity gunnerDriver)) {
            return false;
        }
        GunnerProfile driverProfile = GunnerProfileManager.INSTANCE.getProfile(
                GunnerProfileManager.INSTANCE.normalizeProfileId(gunnerDriver.getProfileId())
        );
        return driverProfile.getFaction() == faction;
    }

    private static boolean isRelativeHostileGunnerVehicle(GunnerEntity sourceGunner, Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        Entity driver = vehicle.getDriver();
        if (!(driver instanceof GunnerEntity targetGunner)) {
            return false;
        }

        GunnerFaction sourceFaction = sourceGunner.getProfileFaction();
        GunnerFaction targetFaction = targetGunner.getProfileFaction();

        if (sourceFaction == GunnerFaction.ENEMY) {
            return targetFaction == GunnerFaction.FRIENDLY || targetFaction == GunnerFaction.TEAM;
        }
        if (sourceFaction == GunnerFaction.FRIENDLY) {
            return targetFaction == GunnerFaction.ENEMY;
        }
        if (sourceFaction == GunnerFaction.TEAM) {
            if (targetFaction == GunnerFaction.ENEMY) {
                return true;
            }
            if (targetFaction == GunnerFaction.TEAM) {
                Team sourceTeam = sourceGunner.getTeam();
                Team targetTeam = targetGunner.getTeam();
                return sourceTeam == null || targetTeam == null || !targetTeam.isAlliedTo(sourceTeam);
            }
        }
        return false;
    }

    private static boolean isVehicleDrivenByNonAlliedGunner(Entity entity, @Nullable Team alliedTeam) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        Entity driver = vehicle.getDriver();
        if (!(driver instanceof GunnerEntity gunnerDriver)) {
            return false;
        }
        Team driverTeam = gunnerDriver.getTeam();
        return alliedTeam == null || driverTeam == null || !driverTeam.isAlliedTo(alliedTeam);
    }

    private static boolean isVehicleDrivenByPlayer(Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        return vehicle.getDriver() instanceof Player;
    }

    private static boolean isOccupiedVehicle(Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        return vehicle.getDriver() != null;
    }

    private static double score(AbstractVehicle vehicle, WeaponUnit weaponUnit, Entity entity) {
        Vec3 toTarget = entity.position().add(0, entity.getBbHeight() * 0.5, 0)
                .subtract(weaponUnit.worldPivotPosition());
        double distance = toTarget.length();
        Vec3 forward = weaponUnit.worldVec();
        if (forward.lengthSqr() < 1.0E-4) {
            forward = VectorUtil.rotToVec(vehicle.getXRot(), vehicle.getYRot());
        }
        double angle = VectorUtil.angleBetween(forward.normalize(), toTarget.normalize());
        return distance + angle * 32.0;
    }
}
