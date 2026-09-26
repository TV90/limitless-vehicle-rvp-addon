package org.ywzj.rvp.entity.gunner.behavior.runtime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Gunner 单 tick 共享观察快照。
 *
 * <p>所有行为与动作适配器都从同一实例派生候选，首次查询至多遍历一次
 * {@code ServerLevel#getEntities().getAll()}。本类只提供只读观察，不提交意图，也不修改实体。</p>
 */
public final class RVP_GunnerObservationService {

    /** 当前 Gunner；用于在途弹药 Owner 查询。 */
    private final GunnerEntity gunner;
    /** 当前载具；所有半径查询均以其包围盒为中心。 */
    private final AbstractVehicle vehicle;
    /** 当前服务端世界；客户端或非法世界时为 null。 */
    @Nullable
    private final ServerLevel serverLevel;
    /** 已加载且存活实体的单 tick 惰性快照。 */
    private final RVP_GunnerObservationQueryCache<Entity> entityCache;
    /** 实体 Team 单 tick 缓存，Identity 语义避免实体 ID 重用干扰。 */
    private final Map<Entity, Team> teamCache = new IdentityHashMap<>();
    /** 实体对应 Gunner Faction 单 tick 缓存。 */
    private final Map<Entity, RVP_EnumGunnerFaction> factionCache = new IdentityHashMap<>();
    /** 玩家目标到所乘载具的单 tick 归一化缓存。 */
    private final Map<Entity, Entity> normalizedTargetCache = new IdentityHashMap<>();
    /** 实体离地高度单 tick 缓存，单位格。 */
    private final Map<Entity, Double> aglCache = new IdentityHashMap<>();
    /** 按半径缓存锁定当前载具的雷达来源。 */
    private final Map<Double, List<AbstractVehicle>> radarLockSourceCache = new java.util.HashMap<>();
    /** 按半径缓存当前 Gunner 拥有的在途 RVP 弹药。 */
    private final Map<Double, List<RVP_BaseBullet>> ownedProjectileCache = new java.util.HashMap<>();
    /** 派生候选查询次数。 */
    private int queryCount;
    /** 派生查询检查过的快照实体总数。 */
    private long candidateChecks;
    /** 所有查询请求过的最大半径，单位格。 */
    private double maxRequestedRadius;
    /** 首次世界快照遍历耗时，单位纳秒。 */
    private long traversalNanos;

    private RVP_GunnerObservationService(GunnerEntity gunner, AbstractVehicle vehicle) {
        this.gunner = gunner;
        this.vehicle = vehicle;
        this.serverLevel = vehicle.level() instanceof ServerLevel level ? level : null;
        this.entityCache = new RVP_GunnerObservationQueryCache<>(this::loadEntities);
    }

    /** 为当前 Gunner tick 创建独立共享观察实例。 */
    public static RVP_GunnerObservationService create(GunnerEntity gunner, AbstractVehicle vehicle) {
        return new RVP_GunnerObservationService(gunner, vehicle);
    }

    /**
     * 从唯一实体快照筛选指定半径候选，保持旧实现的“载具膨胀包围盒相交”语义。
     */
    public List<Entity> candidates(double radius, Predicate<Entity> filter) {
        if (radius <= 0.0D || serverLevel == null) {
            return List.of();
        }
        queryCount++;
        maxRequestedRadius = Math.max(maxRequestedRadius, radius);
        AABB box = vehicle.getBoundingBox().inflate(radius);
        List<Entity> snapshot = entityCache.snapshot();
        candidateChecks += snapshot.size();
        List<Entity> result = new ArrayList<>();
        for (Entity entity : snapshot) {
            if (entity != vehicle && entity.getBoundingBox().intersects(box) && filter.test(entity)) {
                result.add(entity);
            }
        }
        return List.copyOf(result);
    }

    /** 返回全部已加载存活实体快照；用于距离中心不是本车包围盒的专用查询。 */
    public List<Entity> loadedEntities() {
        queryCount++;
        List<Entity> snapshot = entityCache.snapshot();
        candidateChecks += snapshot.size();
        return snapshot;
    }

    /** 返回指定半径内锁定当前载具的雷达来源载具。 */
    public List<AbstractVehicle> radarLockSources(double radius) {
        return radarLockSourceCache.computeIfAbsent(radius, ignored -> {
            List<AbstractVehicle> result = new ArrayList<>();
            for (Entity entity : candidates(radius, candidate -> candidate instanceof AbstractVehicle)) {
                AbstractVehicle source = (AbstractVehicle) entity;
                // 调用本体载具部件枚举，集中识别当前正在锁定本车的雷达来源。
                for (PartUnit<?> part : source.getPartUnits()) {
                    if (part instanceof RadarUnit radar && radar.isOn() && radar.getLockedEntity() == vehicle) {
                        result.add(source);
                        break;
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    /** 返回指定半径内由当前 Gunner 拥有的在途 RVP 弹药。 */
    public List<RVP_BaseBullet> ownedProjectiles(double radius) {
        return ownedProjectileCache.computeIfAbsent(radius, ignored -> {
            List<RVP_BaseBullet> result = new ArrayList<>();
            for (Entity entity : candidates(radius, candidate -> candidate instanceof RVP_BaseBullet)) {
                RVP_BaseBullet projectile = (RVP_BaseBullet) entity;
                // 调用本体弹药 Owner 入口，只保留属于当前 Gunner 的在途弹体。
                if (projectile.getOwner() == gunner) {
                    result.add(projectile);
                }
            }
            return List.copyOf(result);
        });
    }

    /** 返回实体当前 Team；同一实体在本 tick 只读取一次。 */
    @Nullable
    public Team team(Entity entity) {
        if (!teamCache.containsKey(entity)) {
            teamCache.put(entity, entity.getTeam());
        }
        return teamCache.get(entity);
    }

    /** 返回实体或其载具 Gunner 驾驶员的 Faction；无 Gunner 驾驶员时为 null。 */
    @Nullable
    public RVP_EnumGunnerFaction faction(Entity entity) {
        if (!factionCache.containsKey(entity)) {
            Entity normalized = normalizeTarget(entity);
            // 调用本体载具驾驶员入口，将载具阵营归属到实际 Gunner 驾驶员。
            Entity driver = normalized instanceof AbstractVehicle targetVehicle
                    ? targetVehicle.getDriver() : normalized;
            // 调用项目 Gunner Profile 阵营入口，缓存后续敌我判定所需 Faction。
            RVP_EnumGunnerFaction faction = driver instanceof GunnerEntity targetGunner
                    ? targetGunner.getProfileFaction() : null;
            factionCache.put(entity, faction);
        }
        return factionCache.get(entity);
    }

    /** 将乘坐载具的玩家目标归一化为载具；其他目标保持不变。 */
    @Nullable
    public Entity normalizeTarget(@Nullable Entity target) {
        if (target == null) {
            return null;
        }
        if (!normalizedTargetCache.containsKey(target)) {
            Entity normalized = target instanceof Player player && player.getVehicle() instanceof AbstractVehicle ridden
                    ? ridden : target;
            normalizedTargetCache.put(target, normalized);
        }
        return normalizedTargetCache.get(target);
    }

    /** 返回实体离地高度，单位格；同一实体在本 tick 只做一次地形高度查询。 */
    public double agl(Entity entity) {
        // 调用本体地形高度工具，缓存当前 tick 的离地高度，供飞行与 CIWS 共用。
        return aglCache.computeIfAbsent(entity,
                ignored -> entity.getY() - EntityUtil.getGroundY(entity.level(), entity.position()));
    }

    /** 返回当前统计快照，供 DebugSnapshot 与服务端性能记录器读取。 */
    public Statistics statistics() {
        List<Entity> snapshot = entityCache.loadCount() == 0 ? List.of() : entityCache.snapshot();
        return new Statistics(entityCache.loadCount(), queryCount, snapshot.size(), candidateChecks,
                maxRequestedRadius, traversalNanos, teamCache.size(), factionCache.size(),
                normalizedTargetCache.size(), aglCache.size());
    }

    /** 实际抓取一次已加载存活实体，并记录遍历耗时。 */
    private List<Entity> loadEntities() {
        if (serverLevel == null) {
            return List.of();
        }
        long started = System.nanoTime();
        List<Entity> result = new ArrayList<>();
        // 调用服务端已加载实体索引；该入口在本 Gunner tick 内只允许执行一次。
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (entity.isAlive()) {
                result.add(entity);
            }
        }
        traversalNanos = System.nanoTime() - started;
        return result;
    }

    /** 单 tick 观察性能统计。 */
    public record Statistics(
            /** 世界实体全量遍历次数。 */ int worldTraversals,
            /** 从共享快照派生的查询次数。 */ int queryCount,
            /** 首次快照收集的存活实体数。 */ int snapshotEntities,
            /** 派生查询检查候选的累计次数。 */ long candidateChecks,
            /** 最大请求半径，单位格。 */ double maxRequestedRadius,
            /** 世界遍历耗时，单位纳秒。 */ long traversalNanos,
            /** Team 缓存条目数。 */ int teamCacheEntries,
            /** Faction 缓存条目数。 */ int factionCacheEntries,
            /** 目标归一化缓存条目数。 */ int normalizedTargetCacheEntries,
            /** AGL 缓存条目数。 */ int aglCacheEntries) {

        /** 转成稳定有序 Map，便于调试快照展示。 */
        public Map<String, Long> asDebugMap() {
            Map<String, Long> values = new java.util.LinkedHashMap<>();
            values.put("world_traversals", (long) worldTraversals);
            values.put("queries", (long) queryCount);
            values.put("snapshot_entities", (long) snapshotEntities);
            values.put("candidate_checks", candidateChecks);
            values.put("max_radius", Math.round(maxRequestedRadius));
            values.put("traversal_nanos", traversalNanos);
            values.put("team_cache", (long) teamCacheEntries);
            values.put("faction_cache", (long) factionCacheEntries);
            values.put("normalized_target_cache", (long) normalizedTargetCacheEntries);
            values.put("agl_cache", (long) aglCacheEntries);
            return Collections.unmodifiableMap(values);
        }
    }
}
