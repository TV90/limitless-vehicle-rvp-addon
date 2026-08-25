package org.ywzj.rvp.ecm;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity;
import org.ywzj.rvp.vehicle.BoneEcmPassiveConfig;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 被动电子战防御措施（ECM_PASSIVE）服务端管理器。
 *
 * <p>对齐 {@code RVP_CountermeasureEventHandler} 的注册范式：{@code @Mod.EventBusSubscriber}
 * 总在 Forge 总线注册，处理逻辑全部基于 RVP 自有类与本体的既有 public API，
 * <b>零新增 Mixin</b>（历史教训红线：{@code RadarUnit} 在带毒黑名单，绝不打 mixin）。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li><b>照射检测</b>：遍历各开启雷达的服务端探测表，命中装备 ECM_PASSIVE 的载具时
 *       经 {@link RVP_EcmIff#isHostileIllumination} 判定，敌对照射触发状态机；</li>
 *   <li><b>状态机</b>：空闲 → 激活（分档生成假目标）→ 充能 → 空闲；照射距离低于烧穿
 *       阈值直接清场进充能（用户批示：充能期内再次照射不生成）；</li>
 *   <li><b>假目标管理</b>：生成/重排/清理隐形诱饵实体（NCTR 名、随机漂移速度）；</li>
 *   <li><b>友方禁锁看门狗</b>：友方雷达锁定归属方假目标时立即清除锁定并短暂禁锁
 *       （复用箔条干扰的服务端清锁手法）；敌对方锁定不干预（欺骗目的）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_EcmPassiveManager {

    /** 照射检测与状态机推进间隔（tick）：4 tick 对齐其它对抗系统的节流频率。 */
    private static final int TICK_INTERVAL = 4;
    /** 空闲超时清理阈值（tick）：超时不被照射则清除残余假目标并移除状态（对齐旧版 200t）。 */
    private static final long IDLE_CLEANUP_TICKS = 200L;
    /** 友方误锁假目标的禁锁时长（tick）。 */
    private static final int FRIENDLY_LOCK_COOLDOWN_TICKS = 40;

    private RVP_EcmPassiveManager() {
    }

    /** 载具实体 id → 运行状态。 */
    private static final Map<Integer, RVP_EcmPassiveState> STATES = new HashMap<>();
    /** 载具实体 id → 其管理的假目标实体 id 列表。 */
    private static final Map<Integer, List<Integer>> DECOY_IDS = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        int tick = server.getTickCount();
        if (tick % TICK_INTERVAL != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            // 先快照载具列表再操作：假目标/状态变更会增删实体，避免遍历活列表发散
            List<AbstractVehicle> vehicles = new ArrayList<>();
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle vehicle && vehicle.isAlive()) {
                    vehicles.add(vehicle);
                }
            }
            for (AbstractVehicle vehicle : vehicles) {
                scanRadarsOf(level, vehicle);
            }
            tickStates(level, vehicles);
        }
    }

    /* ==================== 照射检测 ==================== */

    /** 遍历一台载具全部开启雷达的服务端探测表：命中装备 ECM_PASSIVE 的敌对目标即触发。 */
    private static void scanRadarsOf(ServerLevel level, AbstractVehicle observer) {
        for (PartUnit<?> partUnit : observer.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                continue;
            }
            Vec3 radarPos = radar.worldRadarPosition();
            // 遍历该雷达服务端探测表（由客户端 DETECT 回写 + 补扫/炮手全量扫维护）
            List<Entity> detectedList = new ArrayList<>();
            for (RadarUnit.DetectedObject detectedObject : radar.getDetectedEntities().values()) {
                if (detectedObject != null && detectedObject.entity != null && detectedObject.entity.isAlive()) {
                    detectedList.add(detectedObject.entity);
                }
            }
            for (Entity detected : detectedList) {
                if (!(detected instanceof AbstractVehicle ewVehicle) || !ewVehicle.isAlive()) {
                    continue;
                }
                BoneEcmPassiveConfig config = resolveAliveConfig(ewVehicle);
                if (config == null) {
                    continue;
                }
                if (!RVP_EcmIff.isHostileIllumination(observer, ewVehicle)) {
                    continue;
                }
                double distance = ewVehicle.position().distanceTo(radarPos);
                onRadarIlluminated(level, ewVehicle, observer, distance);
            }
            // 友方禁锁看门狗：锁定目标是"对该雷达友方"的假目标 → 清除锁定 + 禁锁
            watchdogFriendlyLock(observer, radar);
        }
    }

    /** 单次敌对照射入口：推进状态机（激活/刷新档位/烧穿清场/充能忽略）。 */
    private static void onRadarIlluminated(ServerLevel level, AbstractVehicle ewVehicle,
                                           AbstractVehicle source, double distance) {
        BoneEcmPassiveConfig config = resolveAliveConfig(ewVehicle);
        if (config == null) {
            return;
        }
        RVP_EcmPassiveState state = STATES.computeIfAbsent(ewVehicle.getId(), k -> new RVP_EcmPassiveState());
        state.setLastIlluminatedGameTime(level.getGameTime());
        state.setLastSourceVehicleId(source.getId());

        double horizontalDistance = horizontalDistance(source.position(), ewVehicle.position());

        // 烧穿：任何状态下低于烧穿距离都立即清场进充能
        if (horizontalDistance < config.burnThroughDistance()) {
            if (state.isActive() || !state.isBurnThrough()) {
                clearDecoys(level, ewVehicle.getId());
                state.setActive(false);
                state.setActiveTicks(0);
                state.setCooldownTicks(config.cooldownTicks());
                state.setBurnThrough(true);
            }
            return;
        }
        state.setBurnThrough(false);

        // 充能期：再次照射不生成（用户批示"进入重新充能期"）
        if (!state.isActive() && state.getCooldownTicks() > 0) {
            return;
        }

        BoneEcmPassiveConfig.Band band = config.resolveBand(horizontalDistance);
        if (band == null) {
            return;
        }
        state.setAppliedBand(band);
        if (!state.isActive()) {
            // 空闲 → 激活
            state.setActive(true);
            state.setActiveTicks(config.activeDurationTicks());
            spawnDecoys(level, ewVehicle, config, band);
        } else if (bandChanged(state, band)) {
            // 激活中档位变化 → 重排假目标分布（沿用旧版 bandChanged→reposition 语义）
            clearDecoys(level, ewVehicle.getId());
            spawnDecoys(level, ewVehicle, config, band);
        }
    }

    private static boolean bandChanged(RVP_EcmPassiveState state, BoneEcmPassiveConfig.Band band) {
        BoneEcmPassiveConfig.Band applied = state.getAppliedBand();
        return applied == null
                || applied.maxDistance() != band.maxDistance()
                || applied.decoyCount() != band.decoyCount()
                || applied.radius() != band.radius();
    }

    /* ==================== 状态机推进 ==================== */

    private static void tickStates(ServerLevel level, List<AbstractVehicle> vehicles) {
        Iterator<Map.Entry<Integer, RVP_EcmPassiveState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, RVP_EcmPassiveState> entry = it.next();
            int ownerId = entry.getKey();
            AbstractVehicle owner = findVehicleById(level, ownerId);
            if (owner == null || !owner.isAlive()) {
                clearDecoys(level, ownerId);
                DECOY_IDS.remove(ownerId);
                it.remove();
                continue;
            }
            RVP_EcmPassiveState state = entry.getValue();
            boolean referenced = false;
            for (AbstractVehicle vehicle : vehicles) {
                if (vehicle.getId() == ownerId) {
                    referenced = true;
                    break;
                }
            }
            BoneEcmPassiveConfig config = resolveAliveConfig(owner);
            if (config == null) {
                // 模块骨块全被打坏：失去能力，清场进充能
                clearDecoys(level, ownerId);
                state.setActive(false);
                state.setActiveTicks(0);
                state.setCooldownTicks(config == null ? 100 : config.cooldownTicks());
                continue;
            }
            if (state.isActive()) {
                state.setActiveTicks(state.getActiveTicks() - TICK_INTERVAL);
                if (state.getActiveTicks() <= 0) {
                    state.setActive(false);
                    state.setCooldownTicks(config.cooldownTicks());
                }
            } else if (state.getCooldownTicks() > 0) {
                state.setCooldownTicks(Math.max(0, state.getCooldownTicks() - TICK_INTERVAL));
            } else if (level.getGameTime() - state.getLastIlluminatedGameTime() > IDLE_CLEANUP_TICKS) {
                // 长时间未被照射：清理残余假目标并移除状态
                clearDecoys(level, ownerId);
                DECOY_IDS.remove(ownerId);
                it.remove();
                continue;
            }
            syncDecoys(level, owner, state, config);
            if (!referenced) {
                break; // 防御式：不应发生（owner 即从本 level 查得）
            }
        }
    }

    /** 激活期间维持假目标数量（补齐被击落/丢失的幻影）。 */
    private static void syncDecoys(ServerLevel level, AbstractVehicle owner,
                                   RVP_EcmPassiveState state, BoneEcmPassiveConfig config) {
        if (!state.isActive() || state.isBurnThrough() || state.getAppliedBand() == null) {
            clearDecoys(level, owner.getId());
            return;
        }
        List<RVP_EcmDecoyEntity> alive = collectManagedDecoys(level, owner.getId());
        int missing = state.getAppliedBand().decoyCount() - alive.size();
        if (missing > 0) {
            for (int i = 0; i < missing; i++) {
                spawnOneDecoy(level, owner, config, state.getAppliedBand());
            }
        }
    }

    /* ==================== 假目标生成 ==================== */

    private static void spawnDecoys(ServerLevel level, AbstractVehicle owner,
                                    BoneEcmPassiveConfig config, BoneEcmPassiveConfig.Band band) {
        clearDecoys(level, owner.getId());
        for (int i = 0; i < band.decoyCount(); i++) {
            spawnOneDecoy(level, owner, config, band);
        }
    }

    private static void spawnOneDecoy(ServerLevel level, AbstractVehicle owner,
                                      BoneEcmPassiveConfig config, BoneEcmPassiveConfig.Band band) {
        var random = level.random;
        // 在散布半径内随机取点（圆盘均匀采样），高度在载具上下 ±20 格内浮动
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = Math.sqrt(random.nextDouble()) * band.radius();
        double x = owner.getX() + Math.cos(angle) * radius;
        double z = owner.getZ() + Math.sin(angle) * radius;
        double y = owner.getY() + (random.nextDouble() - 0.35D) * 30.0D;
        y = Math.max(level.getMinBuildHeight() + 8.0D, Math.min(level.getMaxBuildHeight() - 16.0D, y));

        RVP_EcmDecoyEntity decoy = new RVP_EcmDecoyEntity(RVP_Entities.RVP_ECM_DECOY.get(), level);
        decoy.setPos(x, y, z);
        // 随机航向 + 配置区间内的随机速度（水平漂移，模拟空中目标巡航）
        double heading = random.nextDouble() * Math.PI * 2.0D;
        double speed = config.decoySpeedMin()
                + random.nextDouble() * Math.max(0.0D, config.decoySpeedMax() - config.decoySpeedMin());
        Vec3 drift = new Vec3(Math.sin(heading) * speed, 0.0D, Math.cos(heading) * speed);
        decoy.initDecoy(owner.getId(), config.randomNctrName(random),
                config.activeDurationTicks() + 60, drift);
        level.addFreshEntity(decoy);
        DECOY_IDS.computeIfAbsent(owner.getId(), k -> new ArrayList<>()).add(decoy.getId());

        // 首照即时性：直接写进当前照射源雷达的探测表由 §7.1 的扫描循环自然 detect 完成
        // （探测表条目由本体 detect(Entity) 维护位置引用，无需在此重复调用）
    }

    private static void clearDecoys(ServerLevel level, int ownerVehicleId) {
        List<Integer> ids = DECOY_IDS.remove(ownerVehicleId);
        if (ids == null) {
            return;
        }
        for (int id : ids) {
            Entity entity = level.getEntity(id);
            if (entity instanceof RVP_EcmDecoyEntity decoy && decoy.isAlive()) {
                decoy.discard();
            }
        }
    }

    private static List<RVP_EcmDecoyEntity> collectManagedDecoys(ServerLevel level, int ownerVehicleId) {
        List<RVP_EcmDecoyEntity> out = new ArrayList<>();
        List<Integer> ids = DECOY_IDS.get(ownerVehicleId);
        if (ids == null) {
            return out;
        }
        for (int id : ids) {
            Entity entity = level.getEntity(id);
            if (entity instanceof RVP_EcmDecoyEntity decoy && decoy.isAlive()) {
                out.add(decoy);
            }
        }
        return out;
    }

    /* ==================== 友方禁锁看门狗 ==================== */

    /**
     * 友方雷达锁定归属方假目标 → 清除锁定并短暂禁锁（复用箔条干扰的清锁手法）。
     * 敌对方锁定假目标不干预——那正是被动电子战的欺骗目的。
     */
    private static void watchdogFriendlyLock(AbstractVehicle observer, RadarUnit radar) {
        Entity locked = radar.getLockedEntity();
        if (!(locked instanceof RVP_EcmDecoyEntity decoy)) {
            return;
        }
        if (RVP_EcmIff.isDecoyHostileTo(decoy, observer)) {
            // 敌对方锁定幻影：不干预
            return;
        }
        // 归属方或其友方误锁己方幻影：立即脱锁 + 禁锁
        radar.setLockedEntity(null);
        RVP_ChaffJamState.setCooldown(decoy.getUUID(),
                observer.level().getGameTime(), FRIENDLY_LOCK_COOLDOWN_TICKS);
    }

    /* ==================== 解析辅助 ==================== */

    /**
     * 取该载具第一个仍存活的 ECM_PASSIVE 骨块配置；
     * 全部失效（骨块被打坏）返回 null → 失去被动电子战能力。
     */
    private static BoneEcmPassiveConfig resolveAliveConfig(AbstractVehicle vehicle) {
        Map<String, BoneEcmPassiveConfig> devices =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, BoneEcmPassiveConfig> entry : devices.entrySet()) {
            String boneName = entry.getKey();
            if (org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable.isModuleActive(
                    vehicle.getUUID(), boneName,
                    org.ywzj.rvp.vehicle.BoneModuleType.ECM_PASSIVE)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Nullable
    private static AbstractVehicle findVehicleById(ServerLevel level, int entityId) {
        return level.getEntity(entityId) instanceof AbstractVehicle vehicle ? vehicle : null;
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
