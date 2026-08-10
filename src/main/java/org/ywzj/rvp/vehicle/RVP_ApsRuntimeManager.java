package org.ywzj.rvp.vehicle;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_JammingRuntime;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CApsFlameLink;
import org.ywzj.rvp.network.S2CApsHudSync;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerVehicleFire;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleGrenade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * APS（主动防护系统）服务端运行时（替代被删 {@code AbstractVehicleApsMixin} 的 tick 注入）。
 *
 * <p>APS 为骨骼属性：配置挂载在载具 JSON {@code bone_modules} 的 {@code aps} 子对象上
 * （{@link BoneApsConfig}）。每个<b>传感器骨块</b>（如 {@code aps_sensor_left} / {@code aps_sensor_right}）
 * 各挂一个 APS 模块、各自拥有独立的探测扇区（{@code facing_part} + {@code facing_yaw} + {@code scan_fov}）：
 * 左侧模块只拦截左侧来袭的弹药，右侧模块只拦截右侧来袭的弹药。传感器骨块被击毁（APS 模块失效，由
 * {@link RVP_BoneModuleStateTable} 判定）后该侧扇区失去拦截能力，另一侧仍正常工作；全部失效则 APS 整体禁用。
 * 发射器骨骼（{@code launcher_part}）仅作为拦截弹火焰起始点与开火动画锚点，<b>不参与失效判定</b>。</p>
 *
 * <p>弹药/冷却/动画游标按载具 {@link UUID} 存于独立侧表（载具级共享），由
 * {@code RVP_ApsEventHandler} 每 tick 驱动 {@link #tick(AbstractVehicle)}；经
 * {@link RVP_ApsStateSavedData} 持久化（载具加入世界恢复、离开写回）。</p>
 *
 * <p>消费点（客户端 HUD）不变：{@link S2CApsHudSync} 每变化/定时推送到客户端，
 * 由 {@code RVP_ApsHudState} + {@code RVP_ApsHudOverlay} 渲染。</p>
 */
public final class RVP_ApsRuntimeManager {

    /** 单载具 APS 运行时状态（弹药/冷却/装填为载具级共享，与模块数量无关）。 */
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
        long lastDisabledSyncTick = Long.MIN_VALUE;
    }

    /** APS 全部失效时禁用 HUD 推送节流（tick）。 */
    private static final long APS_DISABLED_NOTIFY_INTERVAL = 100L;

    /** 存活 APS 传感器模块（传感器骨块名 + 配置）。 */
    private record ActiveApsModule(String boneName, BoneApsConfig config) {
    }

    /** 扫描结果：目标弹体 + 负责拦截它的存活模块。 */
    private record ApsTarget(Projectile projectile, ActiveApsModule module) {
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

        Map<String, BoneApsConfig> devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveApsDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return;
        }
        List<ActiveApsModule> activeModules = collectActiveModules(vehicle, devices);
        if (activeModules.isEmpty()) {
            maybeNotifyDisabled(vehicle); // 全部 APS 模块失效（发射器骨块被击毁），APS 整体禁用，HUD 隐藏
            return;
        }
        // 系统参数取第一个存活模块（按骨块声明顺序），弹药/冷却/装填载具级共享
        BoneApsConfig config = activeModules.get(0).config();

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
            // 延迟到点后重新做扇区归属：期间该扇区模块可能已失效，失效则放弃本次拦截
            ActiveApsModule module = resolveTargetModule(vehicle, activeModules, pendingTarget.position().subtract(vehicle.position()));
            if (module == null) {
                clearPendingTarget(state);
                return;
            }
            aimLauncherAt(vehicle, module.config().launcherPart(), pendingTarget.position());
            if (fireInterceptor(vehicle, state, module, pendingTarget)) {
                state.ammoCurrent--;
                state.cooldownRemaining = config.cooldownTick();
                state.reloadProgress = 0;
                clearPendingTarget(state);
                maybeSyncHud(vehicle, state, config);
                return;
            }
            clearPendingTarget(state);
            return;
        }

        if (vehicle.tickCount % config.scanIntervalTick() != 0) {
            return;
        }

        ApsTarget target = findTarget(vehicle, activeModules, config);
        if (target == null) {
            return;
        }
        // 探测到目标后让发射器转向目标方向（复用本体 WeaponUnit 瞄准旋转，视觉跟随）
        aimLauncherAt(vehicle, target.module().config().launcherPart(), target.projectile().position());

        if (config.interceptDelayTick() <= 0) {
            if (fireInterceptor(vehicle, state, target.module(), target.projectile())) {
                state.ammoCurrent--;
                state.cooldownRemaining = config.cooldownTick();
                state.reloadProgress = 0;
                clearPendingTarget(state);
                maybeSyncHud(vehicle, state, config);
            }
            return;
        }

        state.pendingTargetId = target.projectile().getId();
        state.pendingDelayTick = config.interceptDelayTick();
    }

    /* ==================== HUD 同步 ==================== */

    /**
     * APS 全部模块失效时推送禁用快照（ammoMax=0）给客户端，HUD 据此隐藏；
     * 节流推送，避免每 tick 刷包。
     */
    private static void maybeNotifyDisabled(AbstractVehicle vehicle) {
        ApsState state = getOrCreate(vehicle.getUUID());
        long now = vehicle.level().getGameTime();
        if (state.lastDisabledSyncTick >= 0 && now - state.lastDisabledSyncTick < APS_DISABLED_NOTIFY_INTERVAL) {
            return;
        }
        state.lastDisabledSyncTick = now;
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CApsHudSync(vehicle.getId(), 0, 0, 1, 0)
        );
    }

    private static void maybeSyncHud(AbstractVehicle vehicle, ApsState state, BoneApsConfig config) {
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
                        config.ammoMax(),
                        config.reloadOneTick(),
                        state.reloadProgress
                )
        );
    }

    /* ==================== 状态维护 ==================== */

    private static void ensureApsState(ApsState state, BoneApsConfig config) {
        if (!state.initialized) {
            state.ammoCurrent = config.ammoMax();
            state.reloadProgress = 0;
            state.cooldownRemaining = 0;
            state.animationCursor = 0;
            clearPendingTarget(state);
            state.initialized = true;
        }
        if (state.ammoCurrent > config.ammoMax()) {
            state.ammoCurrent = config.ammoMax();
        }
    }

    private static void tickReload(ApsState state, BoneApsConfig config) {
        if (state.ammoCurrent >= config.ammoMax()) {
            state.reloadProgress = 0;
            return;
        }
        state.reloadProgress++;
        if (state.reloadProgress >= config.reloadOneTick()) {
            state.ammoCurrent++;
            state.reloadProgress = 0;
        }
    }

    /* ==================== 存活模块 / 扇区归属 ==================== */

    /** 过滤出存活（未被击毁）的 APS 模块：骨块在侧表中无 APS 失效记录即视为存活。 */
    private static List<ActiveApsModule> collectActiveModules(AbstractVehicle vehicle, Map<String, BoneApsConfig> devices) {
        List<ActiveApsModule> out = new ArrayList<>();
        for (Map.Entry<String, BoneApsConfig> entry : devices.entrySet()) {
            if (RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), entry.getKey(), BoneModuleType.APS)) {
                out.add(new ActiveApsModule(entry.getKey(), entry.getValue()));
            }
        }
        return out;
    }

    /**
     * 判定目标弹体归属哪个存活模块的探测扇区：
     * 目标相对车体位置需落在模块前向（{@code facing_part} + {@code facing_yaw}）的
     * {@code scan_fov/2} 半角锥内，且距离 ≤ {@code detect_radius}。
     * 按骨块声明顺序返回第一个命中的模块；无命中扇区返回 null（左侧模块不拦右侧目标）。
     */
    private static @org.jetbrains.annotations.Nullable ActiveApsModule resolveTargetModule(
            AbstractVehicle vehicle, List<ActiveApsModule> activeModules, Vec3 toTarget) {
        if (toTarget == null || toTarget.lengthSqr() <= 1.0E-8) {
            return null;
        }
        for (ActiveApsModule module : activeModules) {
            BoneApsConfig cfg = module.config();
            Vec3 front = RVP_JammingRuntime.resolveFacing(vehicle, cfg.facingPart(), cfg.facingYawDeg());
            if (front == null || front.lengthSqr() <= 1.0E-8) {
                continue;
            }
            if (toTarget.length() > cfg.detectRadius()) {
                continue;
            }
            if (RVP_GuidanceRuntimeGeometry.withinAngle(front, toTarget, cfg.halfAngleDeg())) {
                return module;
            }
        }
        return null;
    }

    /* ==================== 目标发现与拦截 ==================== */

    /**
     * 让发射器部件瞄准目标世界坐标。复用本体 {@link WeaponUnit#aim(Vec3)} 的瞄准旋转逻辑
     * （受部件 {@code rot_info} 转速限制平滑转向）；服务端调用仅设置瞄准旋转字段，不会发包。
     */
    private static void aimLauncherAt(AbstractVehicle vehicle, @Nullable String launcherPart, Vec3 worldTarget) {
        if (launcherPart == null || launcherPart.isBlank() || worldTarget == null || vehicle.level().isClientSide()) {
            return;
        }
        WeaponUnit weaponUnit = getWeaponUnit(vehicle, launcherPart);
        if (weaponUnit != null) {
            weaponUnit.aim(worldTarget);
        }
    }

    private static ApsTarget findTarget(AbstractVehicle vehicle, List<ActiveApsModule> activeModules, BoneApsConfig config) {
        AABB box = vehicle.getBoundingBox().inflate(config.detectRadius());
        List<Entity> candidates = vehicle.level().getEntities(vehicle, box, entity -> isValidTarget(vehicle, config, entity));
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(
                Comparator.comparingDouble((Entity entity) -> -entity.getDeltaMovement().lengthSqr())
                        .thenComparingDouble(vehicle::distanceToSqr)
        );
        for (Entity entity : candidates) {
            Projectile projectile = (Projectile) entity;
            ActiveApsModule module = resolveTargetModule(vehicle, activeModules,
                    projectile.position().subtract(vehicle.position()));
            if (module != null) {
                return new ApsTarget(projectile, module);
            }
        }
        return null;
    }

    private static boolean isValidTarget(AbstractVehicle vehicle, BoneApsConfig config, Entity entity) {
        if (!(entity instanceof Projectile projectile) || !entity.isAlive() || entity == vehicle) {
            return false;
        }
        double speed = projectile.getDeltaMovement().length();
        if (speed < config.projectileSpeedMin() || speed > config.projectileSpeedMax()) {
            return false;
        }
        if (!config.excludeOwnerProjectile()) {
            return true;
        }
        Entity owner = projectile.getOwner();
        return owner != vehicle && !vehicle.getPassengers().contains(owner);
    }

    private static Projectile getPendingTarget(AbstractVehicle vehicle, ApsState state, BoneApsConfig config) {
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

    private static boolean isValidPendingTarget(AbstractVehicle vehicle, BoneApsConfig config, Projectile projectile) {
        if (!isValidTarget(vehicle, config, projectile)) {
            return false;
        }
        AABB detectBox = vehicle.getBoundingBox().inflate(config.detectRadius());
        return detectBox.intersects(projectile.getBoundingBox());
    }

    private static void clearPendingTarget(ApsState state) {
        state.pendingTargetId = -1;
        state.pendingDelayTick = -1;
    }

    private static boolean fireInterceptor(AbstractVehicle vehicle, ApsState state, ActiveApsModule module, Projectile target) {
        BoneApsConfig config = module.config();
        WeaponUnit spawnWeaponUnit = getWeaponUnit(vehicle, config.launcherPart());
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

        // 服务端广播拦截火焰粒子（无玩家参数 = 全服广播、无距离过滤）——对齐 effect_data 的
        // broadcastTrailParticles：保证远处玩家也能收到拦截火焰粒子包
        if (vehicle.level() instanceof ServerLevel serverLevel) {
            Vec3 dir = interceptCenter.subtract(spawnPos);
            double len = dir.length();
            if (len > 1.0E-4) {
                Vec3 unit = dir.scale(1.0 / len);
                int points = Math.max(4, Math.min(24, (int) Math.ceil(len)));
                for (int i = 0; i <= points; i++) {
                    Vec3 p = spawnPos.add(unit.scale(len * i / points));
                    serverLevel.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
            serverLevel.sendParticles(ParticleTypes.FLAME,
                    interceptCenter.x, interceptCenter.y, interceptCenter.z,
                    6, 0.2D, 0.2D, 0.2D, 0.02D);
        }

        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CApsFlameLink(vehicle.getId(), spawnPos, interceptCenter)
        );

        // 拦截动画固定使用归属模块自己的发射器（左侧模块只动左侧发射器）
        AbstractVehicleWeapon<?> animationWeapon = spawnWeaponUnit.indexedWeapons.get(0);
        Channel.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new ServerVehicleFire(vehicle.getId(), -1, spawnWeaponUnit.getIndex(), animationWeapon.getIndex())
        );
        return true;
    }

    private static boolean interceptProjectiles(AbstractVehicle vehicle, BoneApsConfig config, Vec3 center) {
        double radius = config.interceptRadius();
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
