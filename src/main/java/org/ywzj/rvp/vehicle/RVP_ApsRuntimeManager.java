package org.ywzj.rvp.vehicle;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.config.RVP_ApsConfig;
import org.ywzj.rvp.config.RVP_ApsConfigCache;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CApsFlameLink;
import org.ywzj.rvp.network.S2CApsHudSync;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerVehicleFire;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleGrenade;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * APS（主动防护系统）服务端运行时（替代被删 {@code AbstractVehicleApsMixin} 的 tick 注入）。
 *
 * <p>状态按载具 {@link UUID} 存于独立侧表，由 {@link RVP_ApsEventHandler} 每 tick 驱动
 * {@link #tick(AbstractVehicle)}；弹药/冷却/动画游标经 {@link RVP_ApsStateSavedData}
 * 持久化（载具加入世界恢复、离开写回）。</p>
 *
 * <p>消费点（客户端 HUD）不变：{@link S2CApsHudSync} 每变化/定时推送到客户端，
 * 由 {@code RVP_ApsHudState} + {@code RVP_ApsHudOverlay} 渲染。</p>
 */
public final class RVP_ApsRuntimeManager {

    /** 单载具 APS 运行时状态。 */
    public static final class ApsState {
        int ammoCurrent;
        int reloadProgress;
        int cooldownRemaining;
        int animationCursor;
        int pendingTargetId = -1;
        int pendingDelayTick = -1;
        boolean initialized;
        int lastHudSyncTick = -999999;
        int lastHudAmmoCurrent = -1;
        int lastHudReloadProgress = -1;
    }

    private static final Map<UUID, ApsState> STATES = new HashMap<>();

    private RVP_ApsRuntimeManager() {
    }

    /* ==================== 生命周期 ==================== */

    /** 载具加入世界：从独立存档恢复 APS 状态（若无记录则不初始化，tick 时懒初始化）。 */
    public static void onVehicleJoin(AbstractVehicle vehicle, ServerLevel level) {
        int[] saved = RVP_ApsStateSavedData.get(level).readEntry(vehicle.getUUID());
        if (saved == null) {
            return;
        }
        ApsState state = getOrCreate(vehicle.getUUID());
        state.ammoCurrent = Math.max(0, saved[0]);
        state.reloadProgress = Math.max(0, saved[1]);
        state.cooldownRemaining = Math.max(0, saved[2]);
        state.animationCursor = Math.max(0, saved[3]);
        state.initialized = true;
    }

    /** 载具离开世界：写回存档并清理内存。 */
    public static void onVehicleLeave(AbstractVehicle vehicle, ServerLevel level) {
        ApsState state = STATES.remove(vehicle.getUUID());
        if (state != null && state.initialized) {
            RVP_ApsStateSavedData.get(level).writeEntry(vehicle.getUUID(), new int[]{
                    state.ammoCurrent,
                    state.reloadProgress,
                    state.cooldownRemaining,
                    state.animationCursor
            });
        } else {
            RVP_ApsStateSavedData.get(level).writeEntry(vehicle.getUUID(), null);
        }
    }

    /* ==================== 服务端 tick（替代原 mixin rvp$tickAps） ==================== */

    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide() || vehicle.isRemoved() || vehicle.isDestroyed()) {
            return;
        }

        RVP_ApsConfig config = RVP_ApsConfigCache.get(vehicle.getVehicleId());
        if (!config.isEnabled()) {
            return;
        }

        ApsState state = getOrCreate(vehicle.getUUID());
        ensureApsState(state, config);
        if (state.cooldownRemaining > 0) {
            state.cooldownRemaining--;
        }
        tickReload(state, config);
        maybeSyncHud(vehicle, state, config);

        if (state.ammoCurrent <= 0 || !vehicle.hasPower()) {
            clearPendingTarget(state);
            return;
        }
        if (state.cooldownRemaining > 0) {
            clearPendingTarget(state);
            return;
        }

        Projectile pendingTarget = getPendingTarget(vehicle, state, config);
        if (pendingTarget != null) {
            if (state.pendingDelayTick > 0) {
                state.pendingDelayTick--;
                if (state.pendingDelayTick > 0) {
                    return;
                }
            }
            if (fireInterceptor(vehicle, state, config, pendingTarget)) {
                state.ammoCurrent--;
                state.cooldownRemaining = config.getCooldownTick();
                state.reloadProgress = 0;
                clearPendingTarget(state);
                maybeSyncHud(vehicle, state, config);
                return;
            }
            clearPendingTarget(state);
            return;
        }

        if (vehicle.tickCount % config.getScanIntervalTick() != 0) {
            return;
        }

        Projectile target = findTarget(vehicle, config);
        if (target == null) {
            return;
        }

        if (config.getInterceptDelayTick() <= 0) {
            if (fireInterceptor(vehicle, state, config, target)) {
                state.ammoCurrent--;
                state.cooldownRemaining = config.getCooldownTick();
                state.reloadProgress = 0;
                clearPendingTarget(state);
                maybeSyncHud(vehicle, state, config);
            }
            return;
        }

        state.pendingTargetId = target.getId();
        state.pendingDelayTick = config.getInterceptDelayTick();
    }

    /* ==================== HUD 同步 ==================== */

    private static void maybeSyncHud(AbstractVehicle vehicle, ApsState state, RVP_ApsConfig config) {
        int now = vehicle.tickCount;
        boolean timeDue = now - state.lastHudSyncTick >= 10;
        boolean changed = state.lastHudAmmoCurrent != state.ammoCurrent
                || state.lastHudReloadProgress != state.reloadProgress;
        if (!timeDue && !changed) {
            return;
        }
        state.lastHudSyncTick = now;
        state.lastHudAmmoCurrent = state.ammoCurrent;
        state.lastHudReloadProgress = state.reloadProgress;
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CApsHudSync(
                        vehicle.getId(),
                        state.ammoCurrent,
                        config.getAmmoMax(),
                        config.getReloadOneTick(),
                        state.reloadProgress
                )
        );
    }

    /* ==================== 状态维护 ==================== */

    private static void ensureApsState(ApsState state, RVP_ApsConfig config) {
        if (!state.initialized) {
            state.ammoCurrent = config.getAmmoMax();
            state.reloadProgress = 0;
            state.cooldownRemaining = 0;
            state.animationCursor = 0;
            clearPendingTarget(state);
            state.initialized = true;
        }
        if (state.ammoCurrent > config.getAmmoMax()) {
            state.ammoCurrent = config.getAmmoMax();
        }
    }

    private static void tickReload(ApsState state, RVP_ApsConfig config) {
        if (state.ammoCurrent >= config.getAmmoMax()) {
            state.reloadProgress = 0;
            return;
        }
        state.reloadProgress++;
        if (state.reloadProgress >= config.getReloadOneTick()) {
            state.ammoCurrent++;
            state.reloadProgress = 0;
        }
    }

    /* ==================== 目标发现与拦截 ==================== */

    private static Projectile findTarget(AbstractVehicle vehicle, RVP_ApsConfig config) {
        AABB box = vehicle.getBoundingBox().inflate(config.getDetectRadius());
        List<Entity> candidates = vehicle.level().getEntities(vehicle, box, entity -> isValidTarget(vehicle, config, entity));
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(
                Comparator.comparingDouble((Entity entity) -> -entity.getDeltaMovement().lengthSqr())
                        .thenComparingDouble(vehicle::distanceToSqr)
        );
        return (Projectile) candidates.get(0);
    }

    private static boolean isValidTarget(AbstractVehicle vehicle, RVP_ApsConfig config, Entity entity) {
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

    private static Projectile getPendingTarget(AbstractVehicle vehicle, ApsState state, RVP_ApsConfig config) {
        if (state.pendingTargetId < 0) {
            return null;
        }
        Entity entity = vehicle.level().getEntity(state.pendingTargetId);
        if (!(entity instanceof Projectile projectile)) {
            clearPendingTarget(state);
            return null;
        }
        if (!isValidPendingTarget(vehicle, config, projectile)) {
            clearPendingTarget(state);
            return null;
        }
        return projectile;
    }

    private static boolean isValidPendingTarget(AbstractVehicle vehicle, RVP_ApsConfig config, Projectile projectile) {
        if (!isValidTarget(vehicle, config, projectile)) {
            return false;
        }
        AABB detectBox = vehicle.getBoundingBox().inflate(config.getDetectRadius());
        return detectBox.intersects(projectile.getBoundingBox());
    }

    private static void clearPendingTarget(ApsState state) {
        state.pendingTargetId = -1;
        state.pendingDelayTick = -1;
    }

    private static boolean fireInterceptor(AbstractVehicle vehicle, ApsState state, RVP_ApsConfig config, Projectile target) {
        WeaponUnit spawnWeaponUnit = getWeaponUnit(vehicle, config.getSpawnPartId());
        VehicleGrenade grenadeWeapon = getGrenadeWeapon(spawnWeaponUnit);
        if (spawnWeaponUnit == null || grenadeWeapon == null) {
            return false;
        }

        Vec3 spawnPos = spawnWeaponUnit.worldCurrentBoltPosition();
        Vec3 interceptCenter = target.position();
        boolean intercepted = interceptProjectiles(vehicle, config, interceptCenter);
        if (!intercepted) {
            return false;
        }

        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CApsFlameLink(vehicle.getId(), spawnPos, interceptCenter)
        );

        WeaponUnit animationWeaponUnit = getAnimationWeaponUnit(vehicle, state, config, spawnWeaponUnit);
        if (animationWeaponUnit != null) {
            AbstractVehicleWeapon<?> animationWeapon = animationWeaponUnit.indexedWeapons.get(0);
            Channel.CHANNEL.send(
                    PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                    new ServerVehicleFire(vehicle.getId(), -1, animationWeaponUnit.getIndex(), animationWeapon.getIndex())
            );
        }
        return true;
    }

    private static boolean interceptProjectiles(AbstractVehicle vehicle, RVP_ApsConfig config, Vec3 center) {
        double radius = config.getInterceptRadius();
        double radiusSq = radius * radius;
        AABB box = new AABB(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius
        );
        List<Entity> candidates = vehicle.level().getEntities(vehicle, box, entity -> isValidTarget(vehicle, config, entity));
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
            neutralizeProjectile(projectile);
            intercepted = true;
        }
        return intercepted;
    }

    private static void neutralizeProjectile(Projectile projectile) {
        if (projectile instanceof RVP_BaseBullet bullet) {
            bullet.rvp$detonateByAps();
        } else if (RVP_RadarContactHelper.neutralizeHbmMissile(projectile)) {
            // HBM missiles recurse inside their own damage/explosion chain when hard-killed by damage.
        } else {
            projectile.discard();
        }
        if (projectile.getOwner() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("tips.active_protection_system_intercept"), true);
        }
    }

    /* ==================== 武器/挂架解析 ==================== */

    private static WeaponUnit getAnimationWeaponUnit(AbstractVehicle vehicle, ApsState state, RVP_ApsConfig config, WeaponUnit fallback) {
        List<String> animationPartIds = config.getAnimationPartIds();
        if (animationPartIds.isEmpty()) {
            return fallback;
        }
        int size = animationPartIds.size();
        for (int i = 0; i < size; i++) {
            String partId = animationPartIds.get(Math.floorMod(state.animationCursor + i, size));
            WeaponUnit candidate = getWeaponUnit(vehicle, partId);
            if (candidate != null && !candidate.indexedWeapons.isEmpty()) {
                state.animationCursor = Math.floorMod(state.animationCursor + i + 1, size);
                return candidate;
            }
        }
        return fallback;
    }

    private static WeaponUnit getWeaponUnit(AbstractVehicle vehicle, String partId) {
        if (partId == null || partId.isBlank()) {
            return null;
        }
        PartUnit<?> partUnit = vehicle.getPartUnit(partId).orElse(null);
        return partUnit instanceof WeaponUnit weaponUnit ? weaponUnit : null;
    }

    private static VehicleGrenade getGrenadeWeapon(WeaponUnit weaponUnit) {
        if (weaponUnit == null || weaponUnit.indexedWeapons.isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.indexedWeapons.get(0);
        if (weapon instanceof VehicleGrenade grenadeWeapon && "aps".equals(grenadeWeapon.getData().getGrenade())) {
            return grenadeWeapon;
        }
        return null;
    }

    private static ApsState getOrCreate(UUID vehicleId) {
        return STATES.computeIfAbsent(vehicleId, ignored -> new ApsState());
    }
}
