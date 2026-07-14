package org.ywzj.rvp.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CApsFlameLink;
import org.ywzj.rvp.network.S2CApsHudSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.RVP_ApsConfig;
import org.ywzj.rvp.config.RVP_ApsConfigCache;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerVehicleFire;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleGrenade;

import java.util.Comparator;
import java.util.List;

@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleApsMixin {

    @Unique
    private static final String RVP_APS_AMMO_TAG = "RvpApsAmmoCurrent";

    @Unique
    private static final String RVP_APS_RELOAD_PROGRESS_TAG = "RvpApsReloadProgress";

    @Unique
    private static final String RVP_APS_COOLDOWN_TAG = "RvpApsCooldownRemaining";

    @Unique
    private static final String RVP_APS_ANIM_CURSOR_TAG = "RvpApsAnimationCursor";

    @Unique
    private int rvp$apsAmmoCurrent;

    @Unique
    private int rvp$apsReloadProgress;

    @Unique
    private int rvp$apsCooldownRemaining;

    @Unique
    private int rvp$apsAnimationCursor;

    @Unique
    private int rvp$apsPendingTargetId = -1;

    @Unique
    private int rvp$apsPendingDelayTick = -1;

    @Unique
    private boolean rvp$apsInitialized;

    @Unique
    private int rvp$apsLastHudSyncTick = -999999;

    @Unique
    private int rvp$apsLastHudAmmoCurrent = -1;

    @Unique
    private int rvp$apsLastHudReloadProgress = -1;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void rvp$saveApsState(CompoundTag compound, CallbackInfo ci) {
        if (!rvp$apsInitialized) {
            return;
        }
        compound.putInt(RVP_APS_AMMO_TAG, rvp$apsAmmoCurrent);
        compound.putInt(RVP_APS_RELOAD_PROGRESS_TAG, rvp$apsReloadProgress);
        compound.putInt(RVP_APS_COOLDOWN_TAG, rvp$apsCooldownRemaining);
        compound.putInt(RVP_APS_ANIM_CURSOR_TAG, rvp$apsAnimationCursor);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void rvp$readApsState(CompoundTag compound, CallbackInfo ci) {
        if (!compound.contains(RVP_APS_AMMO_TAG, Tag.TAG_ANY_NUMERIC)) {
            return;
        }
        rvp$apsAmmoCurrent = Math.max(0, compound.getInt(RVP_APS_AMMO_TAG));
        rvp$apsReloadProgress = Math.max(0, compound.getInt(RVP_APS_RELOAD_PROGRESS_TAG));
        rvp$apsCooldownRemaining = Math.max(0, compound.getInt(RVP_APS_COOLDOWN_TAG));
        rvp$apsAnimationCursor = Math.max(0, compound.getInt(RVP_APS_ANIM_CURSOR_TAG));
        rvp$apsInitialized = true;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void rvp$tickAps(CallbackInfo ci) {
        AbstractVehicle vehicle = (AbstractVehicle) (Object) this;
        if (vehicle.level().isClientSide() || vehicle.isRemoved() || vehicle.isDestroyed()) {
            return;
        }

        RVP_ApsConfig config = RVP_ApsConfigCache.get(vehicle.getVehicleId());
        if (!config.isEnabled()) {
            return;
        }

        rvp$ensureApsState(config);
        if (rvp$apsCooldownRemaining > 0) {
            rvp$apsCooldownRemaining--;
        }
        rvp$tickReload(config);
        rvp$maybeSyncHud(vehicle, config);

        if (rvp$apsAmmoCurrent <= 0 || !vehicle.hasPower()) {
            rvp$clearPendingTarget();
            return;
        }
        if (rvp$apsCooldownRemaining > 0) {
            rvp$clearPendingTarget();
            return;
        }

        Projectile pendingTarget = rvp$getPendingTarget(vehicle, config);
        if (pendingTarget != null) {
            if (rvp$apsPendingDelayTick > 0) {
                rvp$apsPendingDelayTick--;
                if (rvp$apsPendingDelayTick > 0) {
                    return;
                }
            }
            if (rvp$fireInterceptor(vehicle, config, pendingTarget)) {
                rvp$apsAmmoCurrent--;
                rvp$apsCooldownRemaining = config.getCooldownTick();
                rvp$apsReloadProgress = 0;
                rvp$clearPendingTarget();
                rvp$maybeSyncHud(vehicle, config);
                return;
            }
            rvp$clearPendingTarget();
            return;
        }

        if (vehicle.tickCount % config.getScanIntervalTick() != 0) {
            return;
        }

        Projectile target = rvp$findTarget(vehicle, config);
        if (target == null) {
            return;
        }

        if (config.getInterceptDelayTick() <= 0) {
            if (rvp$fireInterceptor(vehicle, config, target)) {
                rvp$apsAmmoCurrent--;
                rvp$apsCooldownRemaining = config.getCooldownTick();
                rvp$apsReloadProgress = 0;
                rvp$clearPendingTarget();
                rvp$maybeSyncHud(vehicle, config);
            }
            return;
        }

        rvp$apsPendingTargetId = target.getId();
        rvp$apsPendingDelayTick = config.getInterceptDelayTick();
    }

    @Unique
    private void rvp$maybeSyncHud(AbstractVehicle vehicle, RVP_ApsConfig config) {
        int now = vehicle.tickCount;
        boolean timeDue = now - rvp$apsLastHudSyncTick >= 10;
        boolean changed = rvp$apsLastHudAmmoCurrent != rvp$apsAmmoCurrent
                || rvp$apsLastHudReloadProgress != rvp$apsReloadProgress;
        if (!timeDue && !changed) {
            return;
        }
        rvp$apsLastHudSyncTick = now;
        rvp$apsLastHudAmmoCurrent = rvp$apsAmmoCurrent;
        rvp$apsLastHudReloadProgress = rvp$apsReloadProgress;
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CApsHudSync(
                        vehicle.getId(),
                        rvp$apsAmmoCurrent,
                        config.getAmmoMax(),
                        config.getReloadOneTick(),
                        rvp$apsReloadProgress
                )
        );
    }

    @Unique
    private void rvp$ensureApsState(RVP_ApsConfig config) {
        if (!rvp$apsInitialized) {
            rvp$apsAmmoCurrent = config.getAmmoMax();
            rvp$apsReloadProgress = 0;
            rvp$apsCooldownRemaining = 0;
            rvp$apsAnimationCursor = 0;
            rvp$clearPendingTarget();
            rvp$apsInitialized = true;
        }
        if (rvp$apsAmmoCurrent > config.getAmmoMax()) {
            rvp$apsAmmoCurrent = config.getAmmoMax();
        }
    }

    @Unique
    private void rvp$tickReload(RVP_ApsConfig config) {
        if (rvp$apsAmmoCurrent >= config.getAmmoMax()) {
            rvp$apsReloadProgress = 0;
            return;
        }
        rvp$apsReloadProgress++;
        if (rvp$apsReloadProgress >= config.getReloadOneTick()) {
            rvp$apsAmmoCurrent++;
            rvp$apsReloadProgress = 0;
        }
    }

    @Unique
    private static Projectile rvp$findTarget(AbstractVehicle vehicle, RVP_ApsConfig config) {
        AABB box = vehicle.getBoundingBox().inflate(config.getDetectRadius());
        List<Entity> candidates = vehicle.level().getEntities(vehicle, box, entity -> rvp$isValidTarget(vehicle, config, entity));
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(
                Comparator.comparingDouble((Entity entity) -> -entity.getDeltaMovement().lengthSqr())
                        .thenComparingDouble(vehicle::distanceToSqr)
        );
        return (Projectile) candidates.get(0);
    }

    @Unique
    private static boolean rvp$isValidTarget(AbstractVehicle vehicle, RVP_ApsConfig config, Entity entity) {
        if (!(entity instanceof Projectile projectile) || !entity.isAlive() || entity == vehicle) {
            return false;
        }
        double speed = projectile.getDeltaMovement().length();
        if (speed < config.getProjectileSpeedMin() || speed > config.getProjectileSpeedMax()) {
            return false;
        }
        if (!config.isExcludeOwnerProjectile()) {
            return true;
        }
        Entity owner = projectile.getOwner();
        return owner != vehicle && !vehicle.getPassengers().contains(owner);
    }

    @Unique
    private Projectile rvp$getPendingTarget(AbstractVehicle vehicle, RVP_ApsConfig config) {
        if (rvp$apsPendingTargetId < 0) {
            return null;
        }
        Entity entity = vehicle.level().getEntity(rvp$apsPendingTargetId);
        if (!(entity instanceof Projectile projectile)) {
            rvp$clearPendingTarget();
            return null;
        }
        if (!rvp$isValidPendingTarget(vehicle, config, projectile)) {
            rvp$clearPendingTarget();
            return null;
        }
        return projectile;
    }

    @Unique
    private static boolean rvp$isValidPendingTarget(AbstractVehicle vehicle, RVP_ApsConfig config, Projectile projectile) {
        if (!rvp$isValidTarget(vehicle, config, projectile)) {
            return false;
        }
        AABB detectBox = vehicle.getBoundingBox().inflate(config.getDetectRadius());
        return detectBox.intersects(projectile.getBoundingBox());
    }

    @Unique
    private void rvp$clearPendingTarget() {
        rvp$apsPendingTargetId = -1;
        rvp$apsPendingDelayTick = -1;
    }

    @Unique
    private boolean rvp$fireInterceptor(AbstractVehicle vehicle, RVP_ApsConfig config, Projectile target) {
        WeaponUnit spawnWeaponUnit = rvp$getWeaponUnit(vehicle, config.getSpawnPartId());
        VehicleGrenade grenadeWeapon = rvp$getGrenadeWeapon(spawnWeaponUnit);
        if (spawnWeaponUnit == null || grenadeWeapon == null) {
            return false;
        }

        Vec3 spawnPos = spawnWeaponUnit.worldCurrentBoltPosition();
        Vec3 interceptCenter = target.position();
        boolean intercepted = rvp$interceptProjectiles(vehicle, config, interceptCenter);
        if (!intercepted) {
            return false;
        }

        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CApsFlameLink(vehicle.getId(), spawnPos, interceptCenter)
        );

        WeaponUnit animationWeaponUnit = rvp$getAnimationWeaponUnit(vehicle, config, spawnWeaponUnit);
        if (animationWeaponUnit != null) {
            AbstractVehicleWeapon<?> animationWeapon = animationWeaponUnit.indexedWeapons.get(0);
            Channel.CHANNEL.send(
                    PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                    new ServerVehicleFire(vehicle.getId(), -1, animationWeaponUnit.getIndex(), animationWeapon.getIndex())
            );
        }
        return true;
    }

    @Unique
    private boolean rvp$interceptProjectiles(AbstractVehicle vehicle, RVP_ApsConfig config, Vec3 center) {
        double radius = config.getInterceptRadius();
        double radiusSq = radius * radius;
        AABB box = new AABB(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius
        );
        List<Entity> candidates = vehicle.level().getEntities(vehicle, box, entity -> rvp$isValidTarget(vehicle, config, entity));
        if (candidates.isEmpty()) {
            return false;
        }

        boolean intercepted = false;
        for (Entity entity : candidates) {
            if (!(entity instanceof Projectile projectile) || !projectile.isAlive()) {
                continue;
            }
            if (projectile.position().distanceToSqr(center) > radiusSq) {
                continue;
            }
            rvp$neutralizeProjectile(projectile);
            intercepted = true;
        }
        return intercepted;
    }

    @Unique
    private void rvp$neutralizeProjectile(Projectile projectile) {
        if (projectile instanceof RVP_BaseBullet bullet) {
            bullet.rvp$detonateByAps();
        } else {
            projectile.discard();
        }
        if (projectile.getOwner() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("tips.active_protection_system_intercept"), true);
        }
    }

    @Unique
    private WeaponUnit rvp$getAnimationWeaponUnit(AbstractVehicle vehicle, RVP_ApsConfig config, WeaponUnit fallback) {
        List<String> animationPartIds = config.getAnimationPartIds();
        if (animationPartIds.isEmpty()) {
            return fallback;
        }
        int size = animationPartIds.size();
        for (int i = 0; i < size; i++) {
            String partId = animationPartIds.get(Math.floorMod(rvp$apsAnimationCursor + i, size));
            WeaponUnit candidate = rvp$getWeaponUnit(vehicle, partId);
            if (candidate != null && !candidate.indexedWeapons.isEmpty()) {
                rvp$apsAnimationCursor = Math.floorMod(rvp$apsAnimationCursor + i + 1, size);
                return candidate;
            }
        }
        return fallback;
    }

    @Unique
    private static WeaponUnit rvp$getWeaponUnit(AbstractVehicle vehicle, String partId) {
        if (partId == null || partId.isBlank()) {
            return null;
        }
        PartUnit<?> partUnit = vehicle.getPartUnit(partId).orElse(null);
        return partUnit instanceof WeaponUnit weaponUnit ? weaponUnit : null;
    }

    @Unique
    private static VehicleGrenade rvp$getGrenadeWeapon(WeaponUnit weaponUnit) {
        if (weaponUnit == null || weaponUnit.indexedWeapons.isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.indexedWeapons.get(0);
        if (weapon instanceof VehicleGrenade grenadeWeapon && "aps".equals(grenadeWeapon.getData().getGrenade())) {
            return grenadeWeapon;
        }
        return null;
    }
}
