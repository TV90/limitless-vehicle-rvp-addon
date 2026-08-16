package org.ywzj.rvp.countermeasure;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.entity.weapon.ActiveProtectionGrenadeEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only view of target countermeasures for seeker and guidance decisions.
 *
 * <p>Concrete vehicle skills can later feed this service from part units or
 * capabilities. Keeping the query centralized prevents guidance sources from
 * depending on specific aircraft/tank classes.</p>
 */
public final class RVP_CountermeasureState {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 干扰检测日志节流间隔（tick）：检测每 tick 跑，只在有干扰物计数或触发脱锁时按该间隔输出，避免刷屏。 */
    private static final int JAM_LOG_INTERVAL_TICKS = 20;

    /** 导引头视场内干扰物 2 秒记忆：seekerId → (decoyId → 最近一次进入视场的 tick)。
     * 服务端导弹制导与客户端导弹制导都会读写，须线程安全。 */
    private static final Map<Integer, Map<Integer, Integer>> SEEK_DECOY_MEMORY = new ConcurrentHashMap<>();
    private static final int DECOY_MEMORY_TICKS = 40; // 2 秒

    /** 干扰物扫描距离上限（格）：封顶走廊长度，避免按 guidanceTargetDistanceRange（可达上千格）
     * 做超大 getEntities 箱子扫描导致 TPS 掉刻；干扰物/导弹交战集中在末段，256 格足够覆盖。 */
    private static final double DECOY_SCAN_MAX_DIST = 256.0;

    /** 以目标（机体）为中心的检测球半径（格）：与导引头锥形检测取并集——干扰物由目标抛撒、
     * 拖在机体附近，快速/机动目标抛撒的干扰物会偏离导引头窄锥（预测制导的弹头指向目标前方），
     * 但仍在机体周围，靠该球兜住，避免快速目标几乎无法干扰。 */
    private static final double TARGET_DECOY_RADIUS = 55.0;

    private RVP_CountermeasureState() {}

    public static Result query(
            Entity seeker,
            Entity target,
            RVP_EnumGuidanceType guidanceType,
            RVP_GuidanceActiveConfig config
    ) {
        if (seeker != null && hasInterceptorNear(seeker, 8.0)) {
            return new Result(false, false, false, true);
        }
        if (target == null || config == null) {
            return Result.CLEAR;
        }
        if (usesOpticalLineOfSight(guidanceType)
                && (hasSightObstruction(seeker, target) || isTargetInsideSmoke(target))) {
            return new Result(true, true, false, false);
        }
        boolean flareSensitive = (guidanceType == RVP_EnumGuidanceType.IR
                || guidanceType == RVP_EnumGuidanceType.AIR) && !config.ignoreFlares();
        boolean chaffSensitive = (guidanceType == RVP_EnumGuidanceType.ARH
                || guidanceType == RVP_EnumGuidanceType.SARH) && !config.ignoreChaff();
        RVP_EnumCountermeasureType decoyType = flareSensitive ? RVP_EnumCountermeasureType.FLARE
                : chaffSensitive ? RVP_EnumCountermeasureType.CHAFF : null;
        // 按 RVP_InterferenceData：跟踪期间以弹体指向为轴、maxLockAngle*seekerFovShrinkFactor 为 FOV、
        // guidanceTargetDistanceRange 为距离检测对应干扰物；视场内干扰物数超过 seekerJamLimit 时脱锁
        if (decoyType != null && hasJamInSeekerCone(seeker, target, decoyType, config)) {
            return new Result(false, true, true, false);
        }
        return Result.CLEAR;
    }

    public static Result queryPoint(
            Entity seeker,
            Vec3 targetPos,
            RVP_EnumGuidanceType guidanceType,
            RVP_GuidanceActiveConfig config
    ) {
        if (seeker != null && hasInterceptorNear(seeker, 8.0)) {
            return new Result(false, false, false, true);
        }
        if (seeker == null || targetPos == null || config == null) {
            return Result.CLEAR;
        }
        if (usesOpticalLineOfSight(guidanceType)
                && (hasSightObstruction(seeker, targetPos) || isPointInsideSmoke(targetPos, seeker))) {
            return new Result(true, true, false, false);
        }
        return Result.CLEAR;
    }

    private static boolean usesOpticalLineOfSight(RVP_EnumGuidanceType type) {
        return type == RVP_EnumGuidanceType.IR
                || type == RVP_EnumGuidanceType.AIR
                || type == RVP_EnumGuidanceType.LH
                || type == RVP_EnumGuidanceType.SALH
                || type == RVP_EnumGuidanceType.SACLOS
                || type == RVP_EnumGuidanceType.HITL_TV
                || type == RVP_EnumGuidanceType.LBR;
    }

    /** 按制导类型返回其对应干扰物类型（IR/AIR → 热焰弹，SARH/ARH → 箔条）。 */
    public static RVP_EnumCountermeasureType decoyTypeFor(RVP_EnumGuidanceType guidanceType) {
        if (guidanceType == RVP_EnumGuidanceType.IR || guidanceType == RVP_EnumGuidanceType.AIR) {
            return RVP_EnumCountermeasureType.FLARE;
        }
        if (guidanceType == RVP_EnumGuidanceType.ARH || guidanceType == RVP_EnumGuidanceType.SARH) {
            return RVP_EnumCountermeasureType.CHAFF;
        }
        return null;
    }

    /**
     * 返回弹体导引头检测区域内指定类型最近的干扰物实体（供脱锁后转锁诱饵）。
     * 检测区域 = 导引头锥（锥轴=弹体→目标指向，半角 = maxLockAngle * seekerFovShrinkFactor / 2）
     * ∪ 目标中心 TARGET_DECOY_RADIUS 圆；距离上限 = guidanceTargetDistanceRange（封顶 DECOY_SCAN_MAX_DIST）。
     */
    public static Optional<Entity> findDecoyInSeekerCone(
            Entity seeker, Entity target, RVP_EnumCountermeasureType decoyType, RVP_GuidanceActiveConfig config) {
        if (seeker == null || target == null || decoyType == null) {
            return Optional.empty();
        }
        double halfAngle = decoyConeHalfAngle(config);
        double maxDist = decoyScanMaxDist(config);
        Vec3 seekerPos = seeker.position();
        Vec3 unitAxis = resolveConeAxis(seeker, target);
        if (unitAxis.lengthSqr() <= 1.0E-6) {
            return Optional.empty();
        }
        // 检测区域 = 导引头锥 ∪ 目标中心 40 格圆：O(实体) 遍历已加载实体，替代走廊/目标箱子
        // getEntities 的 O(箱子截面) 扫描（干扰判定只在服务端跑）
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        // 追"记忆力最久"的干扰物：取记忆里最近一次进入检测区域时间最早的干扰物（最先抛撒、
        // 漂移最久、离玩家最远），而非最近的干扰物——最近的那枚通常刚抛出、就在玩家脚下，
        // 导弹追它仍会砸向玩家
        Map<Integer, Integer> mem = SEEK_DECOY_MEMORY.get(seeker.getId());
        Comparator<Entity> byMemoryAge = Comparator.comparingInt(
                e -> mem == null ? Integer.MAX_VALUE : mem.getOrDefault(e.getId(), Integer.MAX_VALUE));
        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : allServerEntities(seeker)) {
            if (entity instanceof RVP_Decoy decoy
                    && decoy.rvp$decoyType() == decoyType && entity.isAlive()
                    && inDecoyRegion(seekerPos, unitAxis, halfAngle, maxDist, targetCenter, entity)) {
                candidates.add(entity);
            }
        }
        Optional<Entity> found = candidates.stream()
                .min(byMemoryAge.thenComparingDouble(entity -> entity.distanceToSqr(seeker)));
        // 转锁诱饵日志（脱锁期间每 tick 调用，节流输出）：脱锁后是否找到可转锁的干扰物
        // （找到 → 导弹转锁诱饵；找不到 → 导弹失去目标）
        if (seeker.tickCount % JAM_LOG_INTERVAL_TICKS == 0) {
            LOGGER.info("[RVP-Jam] 转锁诱饵 seeker={} 类型={} 结果={}",
                    seeker.getId(), decoyType,
                    found.map(e -> "找到 id=" + e.getId() + " 距离=" + Math.round(seeker.distanceTo(e) * 10) / 10.0)
                            .orElse("未找到"));
        }
        return found;
    }

    /**
     * 导引头视场（锥）内对应干扰物数量是否超过 seekerJamLimit。
     * 检测区域 = 导引头锥（锥轴=弹体→目标指向，半角 = maxLockAngle * seekerFovShrinkFactor / 2）
     * ∪ 目标中心 TARGET_DECOY_RADIUS 圆：快速/机动目标拖在机体附近的干扰物即使偏离窄锥也被圆兜住；
     * 距离上限 = guidanceTargetDistanceRange（封顶 DECOY_SCAN_MAX_DIST）。
     */
    private static boolean hasJamInSeekerCone(
            Entity seeker, Entity target, RVP_EnumCountermeasureType decoyType, RVP_GuidanceActiveConfig config) {
        if (seeker == null || target == null) {
            return false;
        }
        if (seeker.isRemoved() || !seeker.isAlive()) {
            SEEK_DECOY_MEMORY.remove(seeker.getId());
            return false;
        }
        // 周期性清理：回收已消失/失效弹体的记忆条目，防止静态表长期增长
        if (SEEK_DECOY_MEMORY.size() > 128) {
            SEEK_DECOY_MEMORY.entrySet().removeIf(e -> {
                Entity s = seeker.level().getEntity(e.getKey());
                return s == null || !s.isAlive() || e.getValue().isEmpty();
            });
        }
        double halfAngle = decoyConeHalfAngle(config);
        double maxDist = decoyScanMaxDist(config);
        Vec3 seekerPos = seeker.position();
        Vec3 axis = resolveConeAxis(seeker, target);
        if (axis.lengthSqr() <= 1.0E-6) {
            return false;
        }
        int now = seeker.tickCount;
        // 检测区域 = 导引头锥 ∪ 目标中心 40 格圆：O(实体) 遍历已加载实体，替代走廊/目标箱子
        // getEntities 的 O(箱子截面) 扫描（干扰判定只在服务端跑）；每 tick 全量扫描（配合
        // 40 tick 视场记忆持续累计），避免节流导致快速通过干扰物区域的导弹漏判、干扰时灵时不灵
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        // 记忆累计：目标机速过快时干扰物会快速离开视场锥，单帧锥内数量不足以触发干扰。
        // 记录每枚干扰物最近一次进入检测区域的时间，2 秒（40 tick）内被"看到过"的干扰物持续计入，
        // 即使下一时刻已脱离检测区域，也会在记忆窗口内累计干扰物数量。
        Map<Integer, Integer> memory = SEEK_DECOY_MEMORY.computeIfAbsent(seeker.getId(), k -> new ConcurrentHashMap<>());
        int coneCount = 0;
        int nearCount = 0;
        for (Entity entity : allServerEntities(seeker)) {
            if (!(entity instanceof RVP_Decoy decoy)
                    || decoy.rvp$decoyType() != decoyType || !entity.isAlive()) {
                continue;
            }
            if (!inDecoyRegion(seekerPos, axis, halfAngle, maxDist, targetCenter, entity)) {
                continue;
            }
            memory.put(entity.getId(), now);
            if (inSeekerCone(seekerPos, axis, halfAngle, maxDist, entity)) {
                coneCount++;
            } else {
                nearCount++;
            }
        }
        // 清理过期记忆（超 2 秒未再进入视场）
        memory.entrySet().removeIf(e -> now - e.getValue() >= DECOY_MEMORY_TICKS);
        int count = memory.size();
        boolean jam = count > config.seekerJamLimit();
        // 干扰检测节流日志：有干扰物计数、触发脱锁、或 memory 非空（排查"有干扰物但没脱锁"的延迟）时按间隔输出
        if (now % JAM_LOG_INTERVAL_TICKS == 0 || jam) {
            LOGGER.info("[RVP-Jam] seeker={} target={} type={} 锥内={} 机体{}格内={} 记忆累计={} 阈值={} 脱锁={}",
                    seeker.getId(), target.getId(), decoyType, coneCount,
                    (int) TARGET_DECOY_RADIUS, nearCount, count,
                    config.seekerJamLimit(), jam);
        }
        if (jam) {
            return true;
        }
        if (memory.isEmpty()) {
            SEEK_DECOY_MEMORY.remove(seeker.getId());
        }
        return false;
    }

    /** 导引头视场锥轴：优先弹体→目标指向（干扰物由目标抛撒、拖在目标后方，必须围绕目标来检测，
     * 而非弹体航向——预测制导（predict_target_pos）的弹体会指向目标前方，快速目标会因此把拖在
     * 后方的干扰物全部排除在锥外，记忆里记不到干扰物，看起来"记忆失效"）；仅弹目共位时退化用航向。 */
    private static Vec3 resolveConeAxis(Entity seeker, Entity target) {
        if (target != null) {
            Vec3 axis = target.getBoundingBox().getCenter().subtract(seeker.position());
            if (axis.lengthSqr() > 1.0E-6) {
                return axis.normalize();
            }
        }
        Vec3 look = seeker.getLookAngle();
        if (look.lengthSqr() > 1.0E-6) {
            return look.normalize();
        }
        return Vec3.ZERO;
    }

    /** 导引头视场锥半角（度）：原始检测 fov = maxLockAngle * seekerFovShrinkFactor / 2（全角折半）。
     * 提前量预测（PIP/比例引导）导弹飞行方向偏目标前方，探测锥放大 1.5 倍，兜住拖后的干扰物。
     * 干扰物检测区域 = 该锥形 ∪ 目标中心 TARGET_DECOY_RADIUS 球（见 {@link #inDecoyRegion}）。 */
    private static double decoyConeHalfAngle(RVP_GuidanceActiveConfig config) {
        double halfAngle = config.maxLockAngle() * config.seekerFovShrinkFactor() * 0.5;
        if (config.predictTargetPos()) {
            halfAngle *= 1.5;
        }
        return halfAngle;
    }

    /** 干扰物是否落入检测区域：导引头锥内，或距目标中心 ≤ TARGET_DECOY_RADIUS。
     * 后者兜住快速/机动目标拖在机体附近的干扰物，避免窄锥漏检。 */
    private static boolean inDecoyRegion(Vec3 seekerPos, Vec3 axis, double halfAngle, double maxDist,
                                         Vec3 targetCenter, Entity entity) {
        if (inSeekerCone(seekerPos, axis, halfAngle, maxDist, entity)) {
            return true;
        }
        Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(targetCenter);
        return toTarget.lengthSqr() <= TARGET_DECOY_RADIUS * TARGET_DECOY_RADIUS;
    }

    /** 干扰物扫描距离上限：guidanceTargetDistanceRange 上界；未配置用大默认。 */
    private static double resolveDecoyScanRadius(RVP_GuidanceActiveConfig config) {
        if (config.targetDistanceRange() == null) {
            return 512.0;
        }
        return RVP_GuidanceRuntimeGeometry.resolveScanRadius(config.targetDistanceRange());
    }

    /** 干扰物判定有效距离：配置距离范围与封顶 DECOY_SCAN_MAX_DIST 的较小值（锥内距离与扫描走廊共用）。 */
    private static double decoyScanMaxDist(RVP_GuidanceActiveConfig config) {
        return Math.min(resolveDecoyScanRadius(config), DECOY_SCAN_MAX_DIST);
    }

    /** 服务端已加载实体（O(实体)）：替代走廊/目标箱子 getEntities 的 O(箱子截面) 扫描。
     * 干扰/脱锁判定只在服务端跑（导弹制导），非服务端返回空。 */
    private static Iterable<Entity> allServerEntities(Entity owner) {
        if (owner.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            return sl.getEntities().getAll();
        }
        return java.util.List.of();
    }

    /** [RVP-DBG] 临时诊断：返回弹体附近最近干扰物的信息（类型/距离/是否在检测区/记忆值）。 */
    @Nullable
    public static String debugNearestDecoyString(
            Entity seeker, Entity target, RVP_EnumCountermeasureType type, RVP_GuidanceActiveConfig config) {
        if (seeker == null || type == null || config == null) {
            return null;
        }
        try {
            double halfAngle = decoyConeHalfAngle(config);
            double maxDist = decoyScanMaxDist(config);
            Vec3 seekerPos = seeker.position();
            Vec3 axis = resolveConeAxis(seeker, target);
            Map<Integer, Integer> mem = SEEK_DECOY_MEMORY.get(seeker.getId());
            // 找最近存活干扰物
            StringBuilder sb = new StringBuilder();
            sb.append("coneHalfAngle=").append(String.format("%.1f", halfAngle))
              .append(" maxDist=").append(String.format("%.0f", maxDist));
            sb.append(" memory={");
            if (mem != null) {
                for (Map.Entry<Integer, Integer> e : mem.entrySet()) {
                    sb.append(e.getKey()).append(":").append(e.getValue()).append(",");
                }
            }
            sb.append("}");
            sb.append(" | 干扰物列表[");
            int shown = 0;
            for (Entity entity : allServerEntities(seeker)) {
                if (!(entity instanceof RVP_Decoy decoy)
                        || decoy.rvp$decoyType() != type || !entity.isAlive()) {
                    continue;
                }
                if (shown >= 8) {
                    break;
                }
                double d = entity.distanceTo(seeker);
                Vec3 toDecoy = entity.getBoundingBox().getCenter().subtract(seekerPos);
                double angle = axis.lengthSqr() < 1.0E-6 ? -1 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, axis.dot(toDecoy.normalize())))));
                boolean inCone = toDecoy.length() <= maxDist && angle <= halfAngle;
                boolean inSphere = target != null && entity.getBoundingBox().getCenter().distanceTo(target.getBoundingBox().getCenter()) <= TARGET_DECOY_RADIUS;
                sb.append(String.format("{id=%d dist=%.0f angle=%.1f cone=%s sphere=%s},",
                        entity.getId(), d, angle, inCone, inSphere));
                shown++;
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    /** 目标是否落在导引头锥内（角度 ≤ halfAngle 且距离 ≤ maxDist）。 */
    private static boolean inSeekerCone(Vec3 seekerPos, Vec3 axis, double halfAngle, double maxDist, Entity entity) {
        Vec3 toDecoy = entity.getBoundingBox().getCenter().subtract(seekerPos);
        if (toDecoy.lengthSqr() <= 1.0E-6 || toDecoy.length() > maxDist) {
            return false;
        }
        return RVP_GuidanceRuntimeGeometry.withinAngle(axis, toDecoy, halfAngle);
    }

    /** 目标是否处于任一存活烟雾云的 AABB（禁视区）内（按目标中心点判定）→ IR/AIR 脱锁进惯导。 */
    private static boolean isTargetInsideSmoke(Entity target) {
        return target != null && isPointInsideSmoke(target.getBoundingBox().getCenter(), target);
    }

    /** 位置是否处于任一存活烟雾云的 AABB（禁视区）内。 */
    private static boolean isPointInsideSmoke(Vec3 point, @Nullable Entity near) {
        if (point == null || near == null) {
            return false;
        }
        Level level = near.level();
        AABB probe = new AABB(point, point).inflate(1.0);
        return level.getEntities(near, probe, e -> e instanceof RVP_SmokeEntity && e.isAlive()).stream()
                .anyMatch(e -> e.getBoundingBox().contains(point));
    }

    private static boolean hasSightObstruction(Entity seeker, Entity target) {
        if (target instanceof SightObstruction) {
            return true;
        }
        if (isLineOfSightBlocked(seeker, target.getBoundingBox().getCenter())) {
            return true;
        }
        // 弹目视线走廊：任一 SightObstruction（含大体积烟雾云）的 AABB 与之相交即视为遮挡
        AABB box = new AABB(seeker.position(), target.position()).inflate(2.0);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof SightObstruction).stream()
                .anyMatch(entity -> entity.getBoundingBox().intersects(box));
    }

    private static boolean hasSightObstruction(Entity seeker, Vec3 targetPos) {
        if (isLineOfSightBlocked(seeker, targetPos)) {
            return true;
        }
        AABB box = new AABB(seeker.position(), targetPos).inflate(2.0);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof SightObstruction).stream()
                .anyMatch(entity -> entity.getBoundingBox().intersects(box));
    }

    /**
     * 弹目间方块视线检测，但绝不进入未加载区块。
     *
     * <p>原版 {@code Level.clip} 每一步都走 {@code Level.getBlockState}，服务端实现内部调用
     * {@code getChunk(..., allowLoading=true)}：射线一旦进入未加载区块就会同步加载该区块，
     * 单人游戏里会因此冻结游戏整个区块生成时长（例如在视距外以 1200 格射线指定目标点）。
     * 因此先按已加载区块边界裁剪射线再 clip；射线起点即未加载时视为视线通畅。</p>
     */
    private static boolean isLineOfSightBlocked(Entity seeker, Vec3 targetPos) {
        Level level = seeker.level();
        Vec3 clampEnd = clampRayToLoadedChunks(level, seeker.position(), targetPos);
        if (clampEnd == null) {
            return false;
        }
        return level.clip(new ClipContext(seeker.position(), clampEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, seeker)).getType() != HitResult.Type.MISS;
    }

    /** 把射线终点回退到最后一个已加载区块内；起点即未加载时返回 null（不做方块遮挡判定）。 */
    @Nullable
    private static Vec3 clampRayToLoadedChunks(Level level, Vec3 start, Vec3 end) {
        Vec3 dir = end.subtract(start);
        double dist = dir.length();
        if (dist <= 1.0E-4) {
            return null;
        }
        Vec3 unit = dir.scale(1.0D / dist);
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        double lastLoaded = -1.0D;
        double d = 0.0D;
        while (d <= dist) {
            Vec3 p = start.add(unit.scale(d));
            int cx = Mth.floor(p.x) >> 4;
            int cz = Mth.floor(p.z) >> 4;
            if (cx != lastCx || cz != lastCz) {
                lastCx = cx;
                lastCz = cz;
                if (!level.isLoaded(BlockPos.containing(p))) {
                    break;
                }
            }
            lastLoaded = d;
            d += 1.0D;
        }
        if (lastLoaded < 0.0D) {
            return null;
        }
        return lastLoaded >= dist ? end : start.add(unit.scale(lastLoaded));
    }

    private static boolean hasInterceptorNear(Entity seeker, double radius) {
        AABB box = seeker.getBoundingBox().inflate(radius);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof ActiveProtectionGrenadeEntity).stream()
                .anyMatch(entity -> entity.distanceTo(seeker) < radius);
    }

    public record Result(boolean lockBlocked, boolean jammedInFlight, boolean decoyed, boolean intercepted) {
        public static final Result CLEAR = new Result(false, false, false, false);

        public boolean isDenied() {
            return lockBlocked || jammedInFlight || decoyed || intercepted;
        }
    }
}
