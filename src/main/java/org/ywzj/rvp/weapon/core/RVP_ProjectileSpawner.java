package org.ywzj.rvp.weapon.core;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.debug.RVP_WeaponOriginDebug;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CGpsStateSync;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.gps.GPSTarget;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.function.Supplier;

/**
 * Factory for RVP projectile entities. It centralizes spawn-time data expansion
 * so weapon classes stay focused on firing rules rather than entity wiring.
 */
public final class RVP_ProjectileSpawner {

    private RVP_ProjectileSpawner() {}

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget) {
        return spawn(data, kind, entityType, vehicle, shooter, aim, lockTarget, null, 1f, 0f);
    }

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget, float powerScale, float extraSpread) {
        return spawn(data, kind, entityType, vehicle, shooter, aim, lockTarget, null, powerScale, extraSpread);
    }

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget, WeaponUnit weaponUnit, float powerScale, float extraSpread) {
        return spawn(data, kind, entityType, vehicle, shooter, aim, lockTarget, weaponUnit, powerScale, extraSpread, true);
    }

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget, WeaponUnit weaponUnit, float powerScale,
                                       float extraSpread, boolean includeFireSpread) {
        Level level = vehicle.level();
        float spread = Math.max(includeFireSpread ? data.getInaccuracy() : 0f, 0f) + Math.max(extraSpread, 0f);
        float xRot = aim.direction.x + randomSpread(level, spread);
        float yRot = aim.direction.y + randomSpread(level, spread);
        Vec3 direction = VectorUtil.rotToVec(xRot, yRot).normalize();
        float muzzleSpeed = data.resolveMuzzleSpeed(kind);
        Vec3 motion = direction.scale(Math.max(muzzleSpeed * powerScale, 0.01f));
        RVP_BaseBullet projectile = create(kind, entityType.get(), level, data);
        if (projectile == null) {
            return null;
        }

        Vec3 muzzle = RVP_AimContexts.muzzle(aim);
        RVP_WeaponOriginDebug.noteSpawnInvocation(
                vehicle,
                new RVP_WeaponOriginDebug.ResourceRef(
                        data.getWeaponId() == null ? null : data.getWeaponId().toString(),
                        kind == null ? "<null>" : kind.name()
                ),
                weaponUnit,
                aim,
                muzzle,
                motion,
                xRot,
                yRot,
                includeFireSpread,
                powerScale,
                extraSpread
        );
        projectile.initFromWeapon(data, kind, vehicle, shooter, muzzle,
                new RVP_BaseBullet.AimRot(xRot, yRot), motion);
        projectile.setShooterWeaponUnit(weaponUnit);
        projectile.name = Component.translatable(data.getName());
        if (weaponUnit != null) {
            int weaponIndex = weaponUnit.getCurrentWeapon().map(AbstractVehicleWeapon::getIndex).orElse(0);
            projectile.bindProgrammableAirburstRange(weaponUnit, weaponIndex);
        }

        if (lockTarget != null) {
            projectile.setTargetEntity(lockTarget);
            projectile.markLaunchTargetSnapshot();
        }

        GPSTarget gps = data.usesGuidanceType(RVP_EnumGuidanceType.GPS)
                ? GPSTargetManager.consumeAssignedTarget(shooter, level.dimension().location())
                : null;
        if (gps != null
                && gps.dimension().equals(level.dimension().location())
                && data.usesGuidanceType(RVP_EnumGuidanceType.GPS)) {
            projectile.setTargetPos(gps.pos());
            if (shooter instanceof ServerPlayer player) {
                RVP_Network.CHANNEL.sendTo(
                        S2CGpsStateSync.of(GPSTargetManager.snapshot(player)),
                        player.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
            }
        } else {
            Vec3 impact = RVP_AimContexts.impactPoint(aim);
            if (impact != null && projectile.getTargetPos() == null && shouldSeedLaunchTarget(data, kind)) {
                projectile.setTargetPos(impact);
            }
        }

        if (data.isInheritVehicleVelocity()) {
            projectile.setDeltaMovement(projectile.getDeltaMovement().add(vehicle.getDeltaMovement()));
        }
        projectile.finalizeSpawnOrientation(new RVP_BaseBullet.AimRot(xRot, yRot));

        level.addFreshEntity(projectile);
        return projectile;
    }

    private static boolean shouldSeedLaunchTarget(RVP_WeaponData data, RVP_EnumWeaponKind kind) {
        if (kind == RVP_EnumWeaponKind.MISSILE) {
            return true;
        }
        return kind == RVP_EnumWeaponKind.BOMB
                && data != null
                && data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS);
    }

    private static float randomSpread(Level level, float spread) {
        if (spread <= 0f) {
            return 0f;
        }
        return (level.random.nextFloat() - 0.5f) * spread;
    }

    /**
     * Random pitch/yaw offset (degrees) for one shotgun volley center; pellets add {@code canister_diff} on top.
     */
    public static float[] sampleSpreadCenter(Level level, float spreadDegrees) {
        return new float[]{
                randomSpread(level, spreadDegrees),
                randomSpread(level, spreadDegrees)
        };
    }

    @SuppressWarnings("unchecked")
    private static RVP_BaseBullet create(RVP_EnumWeaponKind kind, EntityType<? extends Projectile> type,
                                         Level level, RVP_WeaponData data) {
        return switch (kind) {
            case MISSILE -> new RVP_MissileEntity((EntityType<? extends Projectile>) type, level, data.getWeaponId());
            case ROCKET -> new RVP_RocketEntity((EntityType<? extends Projectile>) type, level, data.getWeaponId());
            case MACHINEGUN -> new RVP_BulletEntity((EntityType<? extends Projectile>) type, level, data.getWeaponId());
            case BOMB -> new RVP_BombEntity((EntityType<? extends Projectile>) type, level, data.getWeaponId());
            case DISPENSER -> new RVP_DispensedEntity((EntityType<? extends Projectile>) type, level, data.getWeaponId());
            case LASER, TARGETING_POD -> null;
        };
    }
}
