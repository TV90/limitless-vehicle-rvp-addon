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
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.weapon.AerialBombEntity;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
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
        boolean launcher = GunnerBrain.hasLauncherDeployConfig(vehicle);
        List<Entity> entities = vehicle.level().getEntities(vehicle, box, entity ->
                isValidTarget(gunner, vehicle, vehicleTeam, gunnerTeam, entity, profile)
                        && GunnerWeaponSuitability.hasUsableWeaponForTarget(weaponUnit, entity));
        List<Entity> rvpAmmo = entities.stream()
                .filter(entity -> isInterceptableRvpProjectile(entity))
                .toList();
        if (!rvpAmmo.isEmpty()) {
            return rvpAmmo.stream()
                    .min(Comparator.comparingDouble(entity -> score(vehicle, weaponUnit, entity, launcher)))
                    .orElse(null);
        }
        List<Entity> hostileGunnerVehicles = entities.stream()
                .filter(entity -> isRelativeHostileGunnerVehicle(gunner, entity))
                .toList();
        List<Entity> preferred = hostileGunnerVehicles.isEmpty() ? entities : hostileGunnerVehicles;
        return preferred.stream()
                .min(Comparator.comparingDouble(entity -> score(vehicle, weaponUnit, entity, launcher)))
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
                && !isFriendlyAmmoOwner(gunner, vehicle, vehicleTeam, gunnerTeam, ammo.getOwner()));
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
        if (ammo instanceof RocketEntity) {
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
        if (entity instanceof AmmoEntity ammo) {
            if (ammo.vehicle == vehicle) {
                return false;
            }
            if (isFriendlyAmmoOwner(gunner, vehicle, vehicleTeam, gunnerTeam, ammo.getOwner())) {
                return false;
            }
        }
        if (entity instanceof Player player) {
            if (isProtectedCreativePlayer(vehicle, player)) {
                return false;
            }
        }
        if (entity instanceof AbstractVehicle targetVehicle && hasProtectedCreativePassenger(vehicle, targetVehicle)) {
            return false;
        }
        TargetMatch match = matchProfileTarget(gunner, vehicle, entity, profile);
        if (!match.allowed) {
            return false;
        }
        // ENEMY faction 无差别攻击所有人，包括放置者自己
        if (gunner.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY && gunner.isOwnedBy(entity)) {
            return false;
        }
        if (shouldApplyTeamFilter(entity, profile.getFaction())
                && !match.bypassTeamFilter
                && (isAllied(entity, vehicleTeam) || isAllied(entity, gunnerTeam))) {
            return false;
        }
        return true;
    }

    private static boolean isProtectedCreativePlayer(AbstractVehicle sourceVehicle, Player player) {
        if (player.isSpectator()) {
            return true;
        }
        return player.isCreative() && sourceVehicle.level().getDifficulty() != Difficulty.HARD;
    }

    private static boolean hasProtectedCreativePassenger(AbstractVehicle sourceVehicle, AbstractVehicle targetVehicle) {
        for (Entity passenger : targetVehicle.getPassengers()) {
            if (passenger instanceof Player player && isProtectedCreativePlayer(sourceVehicle, player)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldApplyTeamFilter(Entity entity, RVP_EnumGunnerFaction faction) {
        if (faction == RVP_EnumGunnerFaction.ENEMY) {
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

    /**
     * 统一判断弹药owner是否对当前gunner友方（不拦截）
     * - owner == gunner：自己发射的弹药
     * - owner是GunnerEntity：同faction视为友方（敌对gunner之间无team但同faction应互视为友方）
     * - owner是Player或其他：走原有的载具乘客/放置者/team联盟判断
     */
    private static boolean isFriendlyAmmoOwner(GunnerEntity gunner, AbstractVehicle vehicle,
                                               @Nullable Team vehicleTeam, @Nullable Team gunnerTeam,
                                               @Nullable Entity owner) {
        if (owner == null) {
            return false;
        }
        if (owner == gunner) {
            return true;
        }
        if (owner instanceof GunnerEntity ownerGunner) {
            return ownerGunner.getProfileFaction() == gunner.getProfileFaction();
        }
        if (vehicle.getPassengers().contains(owner)) {
            return true;
        }
        // ENEMY faction 无差别攻击所有人，包括放置者自己
        if (gunner.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY && gunner.isOwnedBy(owner)) {
            return true;
        }
        return isAllied(owner, vehicleTeam) || isAllied(owner, gunnerTeam);
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
            if ("rvp:missile".equals(type) && isInterceptableRvpProjectile(entity)) {
                return TargetMatch.allowed(false);
            }
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
            if ("vehicle:enemy_gunner".equals(type) && isVehicleDrivenByFaction(entity, RVP_EnumGunnerFaction.ENEMY)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:friendly_gunner".equals(type) && isVehicleDrivenByFaction(entity, RVP_EnumGunnerFaction.FRIENDLY)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:team_gunner".equals(type) && isVehicleDrivenByFaction(entity, RVP_EnumGunnerFaction.TEAM)) {
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

    private static boolean isRvpMissile(Entity entity) {
        return entity instanceof RVP_MissileEntity || entity instanceof MissileEntity;
    }

    private static boolean isRvpBomb(Entity entity) {
        return entity instanceof AerialBombEntity || entity instanceof RVP_BombEntity;
    }

    private static boolean isRvpRocket(Entity entity) {
        return entity instanceof RVP_RocketEntity || entity instanceof RocketEntity;
    }

    private static boolean isInterceptableRvpProjectile(Entity entity) {
        return isRvpMissile(entity) || isRvpBomb(entity) || isRvpRocket(entity);
    }

    /**
     * 搜索普通搜索范围内的RVP弹药（导弹/炸弹），无高度限制，每tick调用
     */
    @Nullable
    public static AmmoEntity findNearbyAmmoTarget(GunnerEntity gunner, AbstractVehicle vehicle, WeaponUnit weaponUnit, GunnerProfile profile) {
        double radius = getTargetSearchRadius(vehicle, profile);
        AABB box = vehicle.getBoundingBox().inflate(radius);
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        List<Entity> entities = vehicle.level().getEntities(vehicle, box, entity ->
                isValidTarget(gunner, vehicle, vehicleTeam, gunnerTeam, entity, profile)
                        && (isRvpMissile(entity) || isRvpBomb(entity) || isRvpRocket(entity))
                        && GunnerWeaponSuitability.hasUsableWeaponForTarget(weaponUnit, entity));
        if (entities.isEmpty()) {
            return null;
        }
        return (AmmoEntity) entities.stream()
                .min(Comparator.comparingDouble(entity -> score(vehicle, weaponUnit, entity)))
                .orElse(null);
    }

    /**
     * CIWS target search: find the closest non-allied RVP missile or bomb
     * within 1000m that is above 50m AGL.
     */
    @Nullable
    public static AmmoEntity findCiwsTarget(GunnerEntity gunner, AbstractVehicle vehicle) {
        final double ciwsRange = 1000.0;
        final double minAgl = GunnerBrain.hasLauncherDeployConfig(vehicle) ? 0.0 : 50.0;
        AABB box = vehicle.getBoundingBox().inflate(ciwsRange);
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        List<Entity> candidates = vehicle.level().getEntities(vehicle, box, entity -> {
            if (!(entity instanceof AmmoEntity ammo)) {
                return false;
            }
            if (!ammo.isAlive()) {
                return false;
            }
            if (ammo.vehicle == vehicle) {
                return false;
            }
            if (isFriendlyAmmoOwner(gunner, vehicle, vehicleTeam, gunnerTeam, ammo.getOwner())) {
                return false;
            }
            if (!isInterceptableRvpProjectile(entity)) {
                return false;
            }
            if (gunner.isCiwsTargetOnCooldown(entity)) {
                return false;
            }
            double ammoAgl = entity.getY() - entity.level().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    net.minecraft.util.Mth.floor(entity.getX()),
                    net.minecraft.util.Mth.floor(entity.getZ()));
            return ammoAgl >= minAgl;
        });
        if (candidates.isEmpty()) {
            return null;
        }
        Vec3 gunnerPos = gunner.position();
        Entity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Entity entity : candidates) {
            double distSqr = gunnerPos.distanceToSqr(entity.position());
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = entity;
            }
        }
        return (AmmoEntity) best;
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

    private static boolean isVehicleDrivenByFaction(Entity entity, RVP_EnumGunnerFaction faction) {
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

        RVP_EnumGunnerFaction sourceFaction = sourceGunner.getProfileFaction();
        RVP_EnumGunnerFaction targetFaction = targetGunner.getProfileFaction();

        if (sourceFaction == RVP_EnumGunnerFaction.ENEMY) {
            return targetFaction == RVP_EnumGunnerFaction.FRIENDLY || targetFaction == RVP_EnumGunnerFaction.TEAM;
        }
        if (sourceFaction == RVP_EnumGunnerFaction.FRIENDLY) {
            return targetFaction == RVP_EnumGunnerFaction.ENEMY;
        }
        if (sourceFaction == RVP_EnumGunnerFaction.TEAM) {
            if (targetFaction == RVP_EnumGunnerFaction.ENEMY) {
                return true;
            }
            if (targetFaction == RVP_EnumGunnerFaction.TEAM) {
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
        return score(vehicle, weaponUnit, entity, false);
    }

    private static double score(AbstractVehicle vehicle, WeaponUnit weaponUnit, Entity entity, boolean noAnglePenalty) {
        Vec3 toTarget = entity.position().add(0, entity.getBbHeight() * 0.5, 0)
                .subtract(weaponUnit.worldPivotPosition());
        double distance = toTarget.length();
        if (noAnglePenalty) {
            return distance;
        }
        Vec3 forward = weaponUnit.worldVec();
        if (forward.lengthSqr() < 1.0E-4) {
            forward = VectorUtil.rotToVec(vehicle.getXRot(), vehicle.getYRot());
        }
        double angle = VectorUtil.angleBetween(forward.normalize(), toTarget.normalize());
        return distance + angle * 32.0;
    }
}
