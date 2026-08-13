package org.ywzj.rvp.countermeasure;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.entity.weapon.ActiveProtectionGrenadeEntity;

import java.util.Comparator;
import java.util.Optional;

/**
 * Read-only view of target countermeasures for seeker and guidance decisions.
 *
 * <p>Concrete vehicle skills can later feed this service from part units or
 * capabilities. Keeping the query centralized prevents guidance sources from
 * depending on specific aircraft/tank classes.</p>
 */
public final class RVP_CountermeasureState {

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
        if (usesOpticalLineOfSight(guidanceType) && hasSightObstruction(seeker, target)) {
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
        if (usesOpticalLineOfSight(guidanceType) && hasSightObstruction(seeker, targetPos)) {
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
     * 返回弹体导引头视场（锥）内指定类型最近的干扰物实体（供脱锁后转锁诱饵）。
     * 锥轴 = 弹体→目标指向；半角 = maxLockAngle * seekerFovShrinkFactor / 2；距离上限 = guidanceTargetDistanceRange。
     */
    public static Optional<Entity> findDecoyInSeekerCone(
            Entity seeker, Entity target, RVP_EnumCountermeasureType decoyType, RVP_GuidanceActiveConfig config) {
        if (seeker == null || target == null || decoyType == null) {
            return Optional.empty();
        }
        double halfAngle = config.maxLockAngle() * config.seekerFovShrinkFactor() * 0.5;
        double maxDist = resolveDecoyScanRadius(config);
        Vec3 seekerPos = seeker.position();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        Vec3 axis = targetCenter.subtract(seekerPos);
        double axisLen = axis.length();
        if (axisLen <= 1.0E-6) {
            return Optional.empty();
        }
        Vec3 unitAxis = axis.scale(1.0 / axisLen);
        AABB box = new AABB(
                seekerPos.subtract(maxDist, maxDist, maxDist),
                seekerPos.add(maxDist, maxDist, maxDist));
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof RVP_Decoy decoy
                && decoy.rvp$decoyType() == decoyType && entity.isAlive()).stream()
                .filter(entity -> inSeekerCone(seekerPos, unitAxis, halfAngle, maxDist, entity))
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(seeker)));
    }

    /**
     * 导引头视场（锥）内对应干扰物数量是否超过 seekerJamLimit。
     * 锥轴 = 弹体→目标指向；半角 = maxLockAngle * seekerFovShrinkFactor / 2；距离上限 = guidanceTargetDistanceRange。
     */
    private static boolean hasJamInSeekerCone(
            Entity seeker, Entity target, RVP_EnumCountermeasureType decoyType, RVP_GuidanceActiveConfig config) {
        if (seeker == null || target == null) {
            return false;
        }
        double halfAngle = config.maxLockAngle() * config.seekerFovShrinkFactor() * 0.5;
        double maxDist = resolveDecoyScanRadius(config);
        Vec3 seekerPos = seeker.position();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        Vec3 axis = targetCenter.subtract(seekerPos);
        double axisLen = axis.length();
        if (axisLen <= 1.0E-6) {
            return false;
        }
        axis = axis.scale(1.0 / axisLen);
        AABB box = new AABB(
                seekerPos.subtract(maxDist, maxDist, maxDist),
                seekerPos.add(maxDist, maxDist, maxDist));
        int count = 0;
        for (Entity entity : seeker.level().getEntities(seeker, box, e -> e instanceof RVP_Decoy decoy
                && decoy.rvp$decoyType() == decoyType && e.isAlive())) {
            if (inSeekerCone(seekerPos, axis, halfAngle, maxDist, entity)) {
                count++;
                if (count > config.seekerJamLimit()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 干扰物扫描距离上限：guidanceTargetDistanceRange 上界；未配置用大默认。 */
    private static double resolveDecoyScanRadius(RVP_GuidanceActiveConfig config) {
        if (config.targetDistanceRange() == null) {
            return 512.0;
        }
        return RVP_GuidanceRuntimeGeometry.resolveScanRadius(config.targetDistanceRange());
    }

    /** 目标是否落在导引头锥内（角度 ≤ halfAngle 且距离 ≤ maxDist）。 */
    private static boolean inSeekerCone(Vec3 seekerPos, Vec3 axis, double halfAngle, double maxDist, Entity entity) {
        Vec3 toDecoy = entity.getBoundingBox().getCenter().subtract(seekerPos);
        if (toDecoy.lengthSqr() <= 1.0E-6 || toDecoy.length() > maxDist) {
            return false;
        }
        return RVP_GuidanceRuntimeGeometry.withinAngle(axis, toDecoy, halfAngle);
    }

    private static boolean hasSightObstruction(Entity seeker, Entity target) {
        if (target instanceof SightObstruction) {
            return true;
        }
        if (isLineOfSightBlocked(seeker, target.getBoundingBox().getCenter())) {
            return true;
        }
        AABB box = new AABB(seeker.position(), target.position()).inflate(2.0);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof SightObstruction).stream()
                .anyMatch(entity -> entity.distanceTo(target) < 12.0);
    }

    private static boolean hasSightObstruction(Entity seeker, Vec3 targetPos) {
        if (isLineOfSightBlocked(seeker, targetPos)) {
            return true;
        }
        AABB box = new AABB(seeker.position(), targetPos).inflate(2.0);
        return seeker.level().getEntities(seeker, box, entity -> entity instanceof SightObstruction).stream()
                .anyMatch(entity -> distanceToSegment(entity.position(), seeker.position(), targetPos) < 12.0);
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

    private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lenSqr = ab.lengthSqr();
        if (lenSqr <= 1.0E-6) {
            return point.distanceTo(a);
        }
        double t = Math.max(0.0, Math.min(1.0, point.subtract(a).dot(ab) / lenSqr));
        return point.distanceTo(a.add(ab.scale(t)));
    }

    public record Result(boolean lockBlocked, boolean jammedInFlight, boolean decoyed, boolean intercepted) {
        public static final Result CLEAR = new Result(false, false, false, false);

        public boolean isDenied() {
            return lockBlocked || jammedInFlight || decoyed || intercepted;
        }
    }
}
