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
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.vehicle.BoneEcmPassiveConfig;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
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
 * <p><b>一次性脉冲模型（无激活期，用户批示）</b>：被敌对雷达照射时<b>立刻一次性</b>
 * 撒出一波假目标（按距离分档决定数量与散布），随后直接进入充能冷却；冷却期间再次
 * 被照射不生成。假目标是带独立寿命的普通实体——撒出后自主漂移、到期或被击落即消失，
 * 期间不做任何补货（从根上杜绝"反复补货导致无限刷"）。</p>
 *
 * <p>其余职责：</p>
 * <ul>
 *   <li><b>烧穿</b>：照射距离低于阈值时清除现存假目标并刷新充能；</li>
 *   <li><b>友方禁锁看门狗</b>：友方雷达锁定归属方假目标 → 清锁 + 短暂禁锁；
 *       敌对方锁定不干预（欺骗目的）；</li>
 *   <li><b>导引头欺骗（Phase 3）</b>：{@link #tryDivertSeeker} 按 §9 公式掷骰偏转
 *       RVP 自家 ARH/SARH 导引头的截获目标。</li>
 * </ul>
 *
 * <p>零新增 Mixin（历史教训红线：{@code RadarUnit} 带毒黑名单）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_EcmPassiveManager {

    /** 主循环间隔（tick）：4 tick 对齐其它对抗系统的节流频率。 */
    private static final int TICK_INTERVAL = 4;
    /** 空闲超时清理阈值（tick）：超时不被照射则清除残余假目标并移除状态。 */
    private static final long IDLE_CLEANUP_TICKS = 200L;
    /** 友方误锁假目标的禁锁时长（tick）。 */
    private static final int FRIENDLY_LOCK_COOLDOWN_TICKS = 40;

    private RVP_EcmPassiveManager() {
    }

    /** 载具实体 id → 运行状态。 */
    private static final Map<Integer, RVP_EcmPassiveState> STATES = new HashMap<>();
    /** 载具实体 id → 本波脉冲的假目标实体 id 列表。 */
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
            // 收集本 tick 内所有敌对照射（按被照 EW 载具去重，取最近照射源距离）
            Map<Integer, Illumination> illuminations = new HashMap<>();
            for (AbstractVehicle vehicle : vehicles) {
                collectIlluminations(vehicle, illuminations);
            }
            for (Illumination illum : illuminations.values()) {
                onRadarIlluminated(level, illum.ewVehicle(), illum.source(), illum.distance());
            }
            // 友方禁锁看门狗需对每台雷达单独执行（不在去重 map 中）
            for (AbstractVehicle vehicle : vehicles) {
                for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                    if (partUnit instanceof RadarUnit radar && radar.isOn()) {
                        watchdogFriendlyLock(vehicle, radar);
                    }
                }
            }
        }
        // 状态推进只在全部维度处理完后执行一次（跨维度解析归属载具）。
        // 此前放在维度循环内：其它维度的 pass 会因"本维度找不到载具"误删状态
        // → 冷却丢失 → 每 4tick 重新撒波 = 无限刷（已修复的根因）
        tickStates(server);
    }

    /** 本 tick 内的单次照射记录（按被照载具去重后取最近源）。 */
    private record Illumination(AbstractVehicle ewVehicle, AbstractVehicle source, double distance) {}

    /** 收集照射：遍历一台观察者载具的雷达探测表，命中敌对 ECM 载具时记入去重表（距离取最近）。 */
    private static void collectIlluminations(AbstractVehicle observer,
                                             Map<Integer, Illumination> out) {
        for (PartUnit<?> partUnit : observer.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                continue;
            }
            Vec3 radarPos = radar.worldRadarPosition();
            for (RadarUnit.DetectedObject detectedObject : radar.getDetectedEntities().values()) {
                if (detectedObject == null || detectedObject.entity == null || !detectedObject.entity.isAlive()) {
                    continue;
                }
                if (!(detectedObject.entity instanceof AbstractVehicle ewVehicle) || !ewVehicle.isAlive()) {
                    continue;
                }
                if (resolveAliveConfig(ewVehicle) == null) {
                    continue;
                }
                if (!RVP_EcmIff.isHostileIllumination(observer, ewVehicle)) {
                    continue;
                }
                double distance = ewVehicle.position().distanceTo(radarPos);
                Illumination existing = out.get(ewVehicle.getId());
                if (existing == null || distance < existing.distance()) {
                    out.put(ewVehicle.getId(), new Illumination(ewVehicle, observer, distance));
                }
            }
        }
    }

    /**
     * 单次敌对照射入口（一次性脉冲）：
     * 充能完毕且距离命中某档 → 立即撒一波假目标并进入充能；
     * 充能期 → 忽略；烧穿不再是"全局清场"，而是按观察者逐一判定效果（见 {@link #isViewerBurnedThrough}）。
     */
    private static void onRadarIlluminated(ServerLevel level, AbstractVehicle ewVehicle,
                                           AbstractVehicle source, double distance) {
        BoneEcmPassiveConfig config = resolveAliveConfig(ewVehicle);
        if (config == null) {
            return;
        }
        RVP_EcmPassiveState state = STATES.computeIfAbsent(ewVehicle.getId(), k -> new RVP_EcmPassiveState());
        state.setLastIlluminatedGameTime(level.getGameTime());
        state.setLastSourceVehicleId(source.getId());

        // 充能期：再次照射不生成（"一次性脉冲 + 直接进充能"，用户批示）
        if (state.getCooldownTicks() > 0) {
            return;
        }

        BoneEcmPassiveConfig.Band band = config.resolveBand(distance);
        if (band == null) {
            return;
        }
        state.setAppliedBand(band);
        spawnDecoys(level, ewVehicle, config, band);
        // 撒完直接进充能：假目标寿命 ≈ 一个充能周期，衰减殆尽时恰好可以下一波
        state.setCooldownTicks(config.cooldownTicks());
    }

    /**
     * 观察者是否已"烧穿"该假目标（对其实际位置与归属载具的水平距离 &lt; 归属烧穿距离）。
     * 烧穿 = 对该观察者无欺骗效果：不可被其转锁/攻击判定选中；
     * 但假目标本体仍在，不影响其它更远的敌对雷达继续被欺骗（多源互不牵连）。
     */
    public static boolean isViewerBurnedThrough(@Nullable RVP_EcmDecoyEntity decoy, @Nullable Entity viewer) {
        if (decoy == null || viewer == null || !(viewer.level() instanceof ServerLevel sl)) {
            return false;
        }
        Entity owner = sl.getEntity(decoy.getOwnerVehicleId());
        if (!(owner instanceof AbstractVehicle ownerVehicle)) {
            return false;
        }
        BoneEcmPassiveConfig cfg = resolveAliveConfig(ownerVehicle);
        if (cfg == null) {
            return false;
        }
        double dx = viewer.getX() - ownerVehicle.getX();
        double dz = viewer.getZ() - ownerVehicle.getZ();
        return Math.sqrt(dx * dx + dz * dz) < cfg.burnThroughDistance();
    }

    /* ==================== 状态推进 ==================== */

    private static void tickStates(MinecraftServer server) {
        Iterator<Map.Entry<Integer, RVP_EcmPassiveState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, RVP_EcmPassiveState> entry = it.next();
            int ownerId = entry.getKey();
            AbstractVehicle owner = findVehicleAcrossLevels(server, ownerId);
            // 载具消亡 / 模块骨块全被打坏 → 清场移除（用归属者所在维度清理）
            if (owner == null || !owner.isAlive() || resolveAliveConfig(owner) == null) {
                clearDecoysOf(owner, ownerId);
                DECOY_IDS.remove(ownerId);
                it.remove();
                continue;
            }
            RVP_EcmPassiveState state = entry.getValue();
            if (state.getCooldownTicks() > 0) {
                state.setCooldownTicks(Math.max(0, state.getCooldownTicks() - TICK_INTERVAL));
            } else if (owner.level().getGameTime() - state.getLastIlluminatedGameTime() > IDLE_CLEANUP_TICKS) {
                // 长时间未被照射且已可用：清理残余假目标并移除状态
                clearDecoysOf(owner, ownerId);
                DECOY_IDS.remove(ownerId);
                it.remove();
            }
        }
    }

    /* ==================== 假目标生成 ==================== */

    private static void spawnDecoys(ServerLevel level, AbstractVehicle owner,
                                    BoneEcmPassiveConfig config, BoneEcmPassiveConfig.Band band) {
        // 一次性脉冲：先清上一波残余再撒本波（数量=档位上限，此后不再补货）
        clearDecoys(level, owner.getId());
        List<Integer> ids = DECOY_IDS.computeIfAbsent(owner.getId(), k -> new ArrayList<>());
        for (int i = 0; i < band.decoyCount(); i++) {
            spawnOneDecoy(level, owner, config, band, ids);
        }
    }

    private static void spawnOneDecoy(ServerLevel level, AbstractVehicle owner,
                                      BoneEcmPassiveConfig config, BoneEcmPassiveConfig.Band band,
                                      List<Integer> idsOut) {
        var random = level.random;
        // 在散布半径内随机取点；只允许落在【已加载区块】内——落在未加载区块的实体会被卸载，
        // 后续无法按编号解析（采样失败则收半径重试，兜底贴着载具本体生成）
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = band.radius();
        double x = owner.getX();
        double z = owner.getZ();
        double y = owner.getY();
        boolean placed = false;
        for (int attempt = 0; attempt < 8 && !placed; attempt++) {
            double r = radius * Math.sqrt(random.nextDouble());
            x = owner.getX() + Math.cos(angle) * r;
            z = owner.getZ() + Math.sin(angle) * r;
            y = owner.getY() + (random.nextDouble() - 0.35D) * 30.0D;
            y = Math.max(level.getMinBuildHeight() + 8.0D, Math.min(level.getMaxBuildHeight() - 16.0D, y));
            if (level.hasChunkAt(net.minecraft.core.BlockPos.containing(x, y, z))) {
                placed = true;
            } else {
                radius = Math.max(8.0D, radius * 0.5D);
                angle = random.nextDouble() * Math.PI * 2.0D;
            }
        }
        if (!placed) {
            x = owner.getX();
            y = owner.getY();
            z = owner.getZ();
        }

        RVP_EcmDecoyEntity decoy = new RVP_EcmDecoyEntity(RVP_Entities.RVP_ECM_DECOY.get(), level);
        decoy.setPos(x, y, z);
        // 随机航向 + 配置区间内的随机速度（水平漂移，模拟空中目标巡航）
        double heading = random.nextDouble() * Math.PI * 2.0D;
        double speed = config.decoySpeedMin()
                + random.nextDouble() * Math.max(0.0D, config.decoySpeedMax() - config.decoySpeedMin());
        Vec3 drift = new Vec3(Math.sin(heading) * speed, 0.0D, Math.cos(heading) * speed);
        // 寿命 ≈ 一个充能周期：衰减殆尽时恰好可触发下一波，节奏自然
        decoy.initDecoy(owner.getId(), config.randomNctrName(random),
                Math.max(config.activeDurationTicks(), config.cooldownTicks()), drift);
        level.addFreshEntity(decoy);
        idsOut.add(decoy.getId());
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
        // 顺带剪枝：被卸载/已消亡的"幽灵编号"直接从名单剔除，防止计数偏低
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) {
            int id = it.next();
            Entity entity = level.getEntity(id);
            if (entity instanceof RVP_EcmDecoyEntity decoy && decoy.isAlive()) {
                out.add(decoy);
            } else {
                it.remove();
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

    /**
     * Phase 3：RVP 自家 ARH/SARH 导引头截获欺骗。
     * 目标载具存在本波存活假目标时，按距离档基础概率 + 每枚附加掷骰，
     * 成功则把截获目标改判到距真实目标最近的假目标。
     */
    @Nullable
    public static Entity tryDivertSeeker(org.ywzj.rvp.entity.projectile.RVP_BaseBullet projectile,
                                         Entity target, RVP_EnumGuidanceType type) {
        if (type != RVP_EnumGuidanceType.ARH && type != RVP_EnumGuidanceType.SARH) {
            return null;
        }
        if (!(target instanceof AbstractVehicle av) || !av.isAlive()) {
            return null;
        }
        if (!(av.level() instanceof ServerLevel sl)) {
            return null;
        }
        RVP_EcmPassiveState state = STATES.get(av.getId());
        if (state == null) {
            return null;
        }
        BoneEcmPassiveConfig cfg = resolveAliveConfig(av);
        if (cfg == null) {
            return null;
        }
        // 烧穿门控（按观察者逐一判定）：导弹发射载具已进入归属烧穿距离 → 其导引头"看穿"了干扰，
        // 不做偏转；其它更远的发射者不受牵连，继续被假目标欺骗
        Entity shooterVehicle = projectile.getShooterVehicle();
        if (shooterVehicle != null) {
            double dx = shooterVehicle.getX() - av.getX();
            double dz = shooterVehicle.getZ() - av.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < cfg.burnThroughDistance()) {
                return null;
            }
        }
        BoneEcmPassiveConfig.Band band = state.getAppliedBand();
        if (band == null) {
            return null;
        }
        List<RVP_EcmDecoyEntity> decoys = collectManagedDecoys(sl, av.getId());
        if (decoys.isEmpty()) {
            return null;
        }
        double chance = cfg.resolveDiversionChance(band, decoys.size());
        if (sl.random.nextDouble() >= chance) {
            return null;
        }
        // 选距真实目标最近的假目标（最可信的幻影）
        Vec3 targetPos = av.position();
        double best = Double.MAX_VALUE;
        Entity bestDecoy = null;
        for (RVP_EcmDecoyEntity d : decoys) {
            double dsq = d.position().distanceToSqr(targetPos);
            if (dsq < best) {
                best = dsq;
                bestDecoy = d;
            }
        }
        return bestDecoy;
    }

    /** 跨维度按实体 id 解析载具（状态机全局只跑一次，避免被其它维度 pass 误删状态）。 */
    @Nullable
    private static AbstractVehicle findVehicleAcrossLevels(MinecraftServer server, int entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            AbstractVehicle vehicle = findVehicleById(level, entityId);
            if (vehicle != null) {
                return vehicle;
            }
        }
        return null;
    }

    /** 用假目标归属者所在的维度清理其假目标。 */
    private static void clearDecoysOf(@Nullable AbstractVehicle owner, int ownerVehicleId) {
        if (owner != null && owner.level() instanceof ServerLevel sl) {
            clearDecoys(sl, ownerVehicleId);
        }
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
