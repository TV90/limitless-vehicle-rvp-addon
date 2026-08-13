package org.ywzj.rvp.countermeasure.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamHelper;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureData;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureDecoyData;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureStateMachine;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureSystemData;
import org.ywzj.rvp.countermeasure.RVP_DecoyEntity;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.countermeasure.network.S2CCountermeasureHudSync;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.vehicle.all.AllSounds;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerVehicleFire;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 干扰物载具运行时（服务端）。
 *
 * <p>按载具 {@link UUID} 持有两套独立发射状态机（热焰弹 / 箔条）；处理 C2S 按键、每 tick 推进
 * 状态机并生成干扰物实体、广播本体发射动画（借用 launcher_parts 的 WeaponUnit）、雷达箔条判定
 * （脱锁 + 禁锁）；经 {@link RVP_CountermeasureStateSavedData} 持久化。双端安全，纯服务端逻辑。</p>
 */
public final class RVP_CountermeasureRuntimeManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单载具干扰物状态（两套独立状态机）。 */
    public static final class VehicleCountermeasureState {
        public RVP_CountermeasureStateMachine flare;
        public RVP_CountermeasureStateMachine chaff;
        public boolean initialized;
        /** HUD 上次推送 tick（避免 Integer.MIN_VALUE 相减溢出，初始用 -999999）。 */
        public int lastHudSyncTick = -999999;
    }

    private static final Map<UUID, VehicleCountermeasureState> STATES = new HashMap<>();

    private RVP_CountermeasureRuntimeManager() {
    }

    /* ==================== 生命周期 ==================== */

    public static void onVehicleJoin(AbstractVehicle vehicle, ServerLevel level) {
        RVP_CountermeasureData config = resolveConfig(vehicle);
        if (config == null) {
            return;
        }
        VehicleCountermeasureState state = getOrCreate(vehicle.getUUID());
        ensureState(vehicle, state, config);
        int[] saved = RVP_CountermeasureStateSavedData.get(level).readEntry(vehicle.getUUID());
        if (saved != null) {
            if (state.flare != null) {
                state.flare.restore(saved[0], saved[1]);
            }
            if (state.chaff != null) {
                state.chaff.restore(saved[2], saved[3]);
            }
        }
        state.initialized = true;
    }

    public static void onVehicleLeave(AbstractVehicle vehicle, ServerLevel level) {
        VehicleCountermeasureState state = STATES.remove(vehicle.getUUID());
        if (state != null && state.initialized) {
            RVP_CountermeasureStateSavedData.get(level).writeEntry(vehicle.getUUID(), new int[]{
                    state.flare != null ? state.flare.getRemaining() : 0,
                    state.flare != null && state.flare.isReloading() ? state.flare.getReloadProgress() : 0,
                    state.chaff != null ? state.chaff.getRemaining() : 0,
                    state.chaff != null && state.chaff.isReloading() ? state.chaff.getReloadProgress() : 0
            });
        } else {
            RVP_CountermeasureStateSavedData.get(level).writeEntry(vehicle.getUUID(), null);
        }
    }

    /* ==================== C2S 触发 ==================== */

    public static void onFire(ServerPlayer player, AbstractVehicle vehicle, RVP_EnumCountermeasureType type) {
        fire(vehicle, type);
    }

    /** 触发一次齐射（玩家按键或 gunner 自动响应复用同一入口）。 */
    public static void fire(AbstractVehicle vehicle, RVP_EnumCountermeasureType type) {
        if (vehicle == null || vehicle.level().isClientSide() || vehicle.isDestroyed() || !vehicle.hasPower()) {
            return;
        }
        RVP_CountermeasureSystemData system = resolveSystem(vehicle, type);
        if (system == null || !isSystemActive(vehicle, system)) {
            return;
        }
        VehicleCountermeasureState state = getOrCreate(vehicle.getUUID());
        ensureState(vehicle, state, resolveConfig(vehicle));
        machineFor(state, type).onKeyPress();
    }

    /* ==================== 服务端 tick ==================== */

    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide() || vehicle.isRemoved() || vehicle.isDestroyed()) {
            return;
        }
        RVP_CountermeasureData config = resolveConfig(vehicle);
        if (config == null) {
            return;
        }
        VehicleCountermeasureState state = getOrCreate(vehicle.getUUID());
        ensureState(vehicle, state, config);
        tickSystem(vehicle, config, RVP_EnumCountermeasureType.FLARE, state.flare);
        tickSystem(vehicle, config, RVP_EnumCountermeasureType.CHAFF, state.chaff);
        maybeSyncHud(vehicle, state);
        tickRadarChaffJam(vehicle);
    }

    private static void tickSystem(AbstractVehicle vehicle, RVP_CountermeasureData config,
                                   RVP_EnumCountermeasureType type, RVP_CountermeasureStateMachine machine) {
        RVP_CountermeasureSystemData system = config.system(type);
        if (system == null || !isSystemActive(vehicle, system)) {
            return;
        }
        int fireCount = machine.onTick();
        if (fireCount <= 0) {
            return;
        }
        spawnRound(vehicle, system, fireCount, type);
    }

    /** 雷达箔条判定：对每台有锁定目标的雷达做脱锁 + 禁锁（节流，仅在有锁时查询）。 */
    private static void tickRadarChaffJam(AbstractVehicle vehicle) {
        if (vehicle.tickCount % 4 != 0) {
            return;
        }
        long gameTime = vehicle.level().getGameTime();
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof RadarUnit radar) || !radar.isOn()) {
                continue;
            }
            Entity locked = radar.getLockedEntity();
            if (locked == null || !locked.isAlive()) {
                continue;
            }
            RVP_ChaffJamHelper.tryJamLock(vehicle, radar, locked, gameTime);
        }
    }

    /** HUD 状态节流推送（每 10 tick），向跟踪载具的玩家广播剩余 / 装填。 */
    private static void maybeSyncHud(AbstractVehicle vehicle, VehicleCountermeasureState state) {
        int now = vehicle.tickCount;
        if (now - state.lastHudSyncTick < 10) {
            return;
        }
        state.lastHudSyncTick = now;
        int flareTotal = state.flare != null ? state.flare.getTotal() : 0;
        int chaffTotal = state.chaff != null ? state.chaff.getTotal() : 0;
        int flareReload = state.flare != null && state.flare.isReloading()
                ? Math.max(0, state.flare.getReloadTick() - state.flare.getReloadProgress()) : 0;
        int chaffReload = state.chaff != null && state.chaff.isReloading()
                ? Math.max(0, state.chaff.getReloadTick() - state.chaff.getReloadProgress()) : 0;
        S2CCountermeasureHudSync packet = new S2CCountermeasureHudSync(
                vehicle.getId(),
                state.flare != null ? state.flare.getRemaining() : 0, flareTotal, flareReload,
                state.chaff != null ? state.chaff.getRemaining() : 0, chaffTotal, chaffReload);
        LOGGER.info("[RVP-CM] HUD 同步 载具={} flare={}/{} chaff={}/{}", vehicle.getId(), flareTotal, flareReload, chaffTotal, chaffReload);
        // 直接发给载具乘客（驾驶员），保证本地 HUD 一定收到；再向跟踪载具的其它玩家广播
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer serverPlayer) {
                org.ywzj.rvp.network.RVP_Network.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> serverPlayer), packet);
            }
        }
        org.ywzj.rvp.network.RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> vehicle), packet);
    }

    /* ==================== 发射 ==================== */

    private static void spawnRound(AbstractVehicle vehicle, RVP_CountermeasureSystemData system,
                                   int fireCount, RVP_EnumCountermeasureType type) {
        RVP_CountermeasureDecoyData decoy = system.getDecoy();
        List<String> launcherParts = system.getLauncherParts();
        if (launcherParts.isEmpty()) {
            return;
        }
        Vec3 vehicleVelocity = vehicle.getDeltaMovement();
        Vec3 soundPos = null;
        for (int i = 0; i < fireCount; i++) {
            WeaponUnit launcher = resolveLauncher(vehicle, launcherParts.get(i % launcherParts.size()));
            if (launcher == null) {
                continue;
            }
            if (soundPos == null) {
                soundPos = launcher.worldCurrentBoltPosition();
            }
            spawnOne(vehicle, launcher, decoy, type, vehicleVelocity);
            // 每轮只在第一个发射装置上广播一次动画，避免刷包
            if (i == 0) {
                broadcastFireAnimation(vehicle, launcher);
            }
        }
        // 每轮投射播放一次本体干扰弹发射音效（服务端广播给附近玩家）
        if (soundPos != null) {
            vehicle.level().playSound(null, soundPos.x, soundPos.y, soundPos.z,
                    AllSounds.DECOY_FLARE_LAUNCH.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);
            LOGGER.info("[RVP-CM] {} 抛洒 {} 发，@{}", type, fireCount, soundPos);
        }
    }

    private static void spawnOne(AbstractVehicle vehicle, WeaponUnit launcher,
                                 RVP_CountermeasureDecoyData decoy,
                                 RVP_EnumCountermeasureType type, Vec3 vehicleVelocity) {
        Vec3 spawnPos = launcher.worldCurrentBoltPosition();
        // 散布随机偏移
        double spread = decoy.getSpread();
        Vec3 spreadOffset = new Vec3(
                (vehicle.level().random.nextDouble() - 0.5) * 2 * spread,
                (vehicle.level().random.nextDouble() - 0.5) * 2 * spread,
                (vehicle.level().random.nextDouble() - 0.5) * 2 * spread);
        Vec3 dir = launcher.worldVec();
        if (dir.lengthSqr() > 1.0E-6) {
            dir = dir.normalize();
        } else {
            dir = vehicle.getLookAngle();
        }
        RVP_DecoyEntity decoyEntity = new RVP_DecoyEntity(RVP_Entities.RVP_DECOY.get(), vehicle.level());
        decoyEntity.initDecoy(type, decoy);
        decoyEntity.setPos(spawnPos.add(spreadOffset));
        decoyEntity.setDeltaMovement(dir.scale(decoy.getSpeed()).add(vehicleVelocity));
        vehicle.level().addFreshEntity(decoyEntity);
    }

    private static void broadcastFireAnimation(AbstractVehicle vehicle, WeaponUnit launcher) {
        if (launcher.indexedWeapons.isEmpty()) {
            return;
        }
        AbstractVehicleWeapon<?> animationWeapon = launcher.indexedWeapons.get(0);
        // 调用本体网络通道，向所有跟踪载具的玩家广播发射动画（对齐 APS 的 ServerVehicleFire 用法）
        Channel.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new ServerVehicleFire(vehicle.getId(), -1, launcher.getIndex(), animationWeapon.getIndex()));
    }

    /* ==================== 解析 ==================== */

    @Nullable
    private static RVP_CountermeasureData resolveConfig(AbstractVehicle vehicle) {
        return RVP_CountermeasureConfigManager.INSTANCE.resolve(vehicle.getVehicleId());
    }

    @Nullable
    private static RVP_CountermeasureSystemData resolveSystem(AbstractVehicle vehicle, RVP_EnumCountermeasureType type) {
        RVP_CountermeasureData config = resolveConfig(vehicle);
        if (config == null) {
            return null;
        }
        RVP_CountermeasureSystemData system = config.system(type);
        return system != null && system.isEnabled() ? system : null;
    }

    /**
     * 系统是否当前可用：配置启用，且若配置了 {@code bone_modules}，对应骨块的
     * COUNTERMEASURE 模块必须仍有存活（全部被击毁则失去抛洒功能）。
     */
    private static boolean isSystemActive(AbstractVehicle vehicle, RVP_CountermeasureSystemData system) {
        if (system == null || !system.isEnabled()) {
            return false;
        }
        java.util.List<String> bones = system.getBoneModules();
        if (bones.isEmpty()) {
            return true;
        }
        java.util.UUID vehicleId = vehicle.getUUID();
        for (String bone : bones) {
            if (RVP_BoneModuleStateTable.isModuleActive(vehicleId, bone, BoneModuleType.COUNTERMEASURE)) {
                return true;
            }
        }
        return false;
    }

    private static void ensureState(AbstractVehicle vehicle, VehicleCountermeasureState state, @Nullable RVP_CountermeasureData config) {
        if (config == null) {
            return;
        }
        // gunner 驾驶的载具装填时间为玩家的两倍（自动干扰响应更慢补弹）
        int reloadFactor = vehicle.getDriver() instanceof org.ywzj.rvp.entity.gunner.GunnerEntity ? 2 : 1;
        if (state.flare == null) {
            RVP_CountermeasureSystemData flare = config.getFlare();
            state.flare = flare == null || !flare.isEnabled() ? null
                    : new RVP_CountermeasureStateMachine(flare.getTotal(), flare.getPerRound(),
                    flare.getBurstRounds(), flare.getLaunchIntervalTick(), flare.getReloadTick() * reloadFactor);
        }
        if (state.chaff == null) {
            RVP_CountermeasureSystemData chaff = config.getChaff();
            state.chaff = chaff == null || !chaff.isEnabled() ? null
                    : new RVP_CountermeasureStateMachine(chaff.getTotal(), chaff.getPerRound(),
                    chaff.getBurstRounds(), chaff.getLaunchIntervalTick(), chaff.getReloadTick() * reloadFactor);
        }
    }

    @Nullable
    private static RVP_CountermeasureStateMachine machineFor(VehicleCountermeasureState state, RVP_EnumCountermeasureType type) {
        return type == RVP_EnumCountermeasureType.FLARE ? state.flare : state.chaff;
    }

    @Nullable
    private static WeaponUnit resolveLauncher(AbstractVehicle vehicle, String partId) {
        if (partId == null || partId.isBlank()) {
            return null;
        }
        PartUnit<?> partUnit = vehicle.getPartUnit(partId).orElse(null);
        return partUnit instanceof WeaponUnit weaponUnit ? weaponUnit : null;
    }

    private static VehicleCountermeasureState getOrCreate(UUID vehicleId) {
        return STATES.computeIfAbsent(vehicleId, ignored -> new VehicleCountermeasureState());
    }
}
