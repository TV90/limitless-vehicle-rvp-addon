package org.ywzj.rvp.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Plans horizontal chunk paths without loading chunks and checks whether a planned path can tick entities.
 *
 * <p>在不触发区块加载的前提下规划水平区块路径，并检查路径是否具备实体 Tick 条件。</p>
 */
public final class RVP_ChunkPathLoader {
    /** 水平速度平方低于该值时视为没有水平运动，只保留当前区块。 */
    static final double MIN_MOTION_SQR = 1.0E-8D;
    /** 比较 X/Z 边界到达时间时使用的相对误差，用于识别精确穿过区块角点的路径。 */
    private static final double CORNER_EPSILON = 1.0E-12D;
    /** Minecraft 水平区块边长，单位为方块。 */
    private static final int CHUNK_SIZE = 16;

    /** 工具类不允许实例化。 */
    private RVP_ChunkPathLoader() {
    }

    /**
     * Enumerates every horizontal chunk touched by {@code start -> start + motion * horizonTicks}.
     *
     * <p>The result is ordered from the start of the segment, contains no duplicates, and uses
     * supercover corner handling. If {@code maxChunks} is reached, the result remains a continuous
     * prefix; it never samples the endpoint while leaving gaps in the middle.</p>
     *
     * <p>使用二维 Amanatides-Woo DDA（supercover voxel traversal）枚举 X/Z 线段经过的区块：
     * 枚举预测水平线段触及的所有区块。结果按运动方向排序、无重复；精确穿过角点时同时
     * 包含两个侧邻区块和对角区块。达到上限时只保留连续前缀，绝不抽样终点而遗漏中间区块。</p>
     */
    static List<ChunkPos> collectSupercoverChunks(
            @Nullable Vec3 start,
            @Nullable Vec3 motion,
            int horizonTicks,
            int maxChunks) {
        // 起点无效时无法确定当前区块，返回空路径并由上层禁止移动。
        if (!isFinite(start)) {
            return List.of();
        }

        // 即使调用方传入 0 或负上限，也至少保留弹体当前所在区块。
        int limit = Math.max(1, maxChunks);
        ChunkPos startChunk = chunkAt(start.x, start.z);
        // 速度无效时不尝试外推，避免 NaN/Infinity 进入 DDA 循环。
        if (!isFinite(motion)) {
            return List.of(startChunk);
        }

        double horizontalMotionSqr = motion.x * motion.x + motion.z * motion.z;
        // 区块只有 X/Z 维度；纯垂直运动只需要保护当前区块。
        if (!Double.isFinite(horizontalMotionSqr) || horizontalMotionSqr < MIN_MOTION_SQR) {
            return List.of(startChunk);
        }

        // 预测 Tick 数最少按 1 处理，避免调用方用 0 绕过本 Tick 路径检查。
        int horizon = Math.max(1, horizonTicks);
        double endX = start.x + motion.x * horizon;
        double endZ = start.z + motion.z * horizon;
        // 极端速度与预测窗口乘法溢出时安全退回当前区块。
        if (!Double.isFinite(endX) || !Double.isFinite(endZ)) {
            return List.of(startChunk);
        }

        ChunkPos endChunk = chunkAt(endX, endZ);
        List<ChunkPos> chunks = new ArrayList<>(Math.min(limit, 16));
        // 起点区块始终位于结果首位，后续截断也不会将其丢弃。
        chunks.add(startChunk);
        if (startChunk.equals(endChunk)) {
            return List.copyOf(chunks);
        }

        int currentX = startChunk.x;
        int currentZ = startChunk.z;
        int stepX = Double.compare(endX, start.x);
        int stepZ = Double.compare(endZ, start.z);

        double deltaX = endX - start.x;
        double deltaZ = endZ - start.z;
        // tDelta 表示沿归一化线段每跨过一个 X/Z 区块边界所增加的参数距离。
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : CHUNK_SIZE / Math.abs(deltaX);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : CHUNK_SIZE / Math.abs(deltaZ);
        // tMax 表示从起点首次抵达下一条 X/Z 区块边界的参数时间。
        double tMaxX = initialBoundaryTime(start.x, deltaX, currentX, stepX);
        double tMaxZ = initialBoundaryTime(start.z, deltaZ, currentZ, stepZ);

        while (currentX != endChunk.x || currentZ != endChunk.z) {
            // 同时抵达 X/Z 边界表示线段穿过角点；supercover 必须加入两个侧邻块和对角块。
            if (nearlyEqual(tMaxX, tMaxZ)) {
                int nextX = currentX + stepX;
                int nextZ = currentZ + stepZ;
                if (!addChunk(chunks, new ChunkPos(nextX, currentZ), limit)
                        || !addChunk(chunks, new ChunkPos(currentX, nextZ), limit)
                        || !addChunk(chunks, new ChunkPos(nextX, nextZ), limit)) {
                    break;
                }
                currentX = nextX;
                currentZ = nextZ;
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
            } else if (tMaxX < tMaxZ) {
                // 先穿过 X 边界，只向 X 方向推进一个区块。
                currentX += stepX;
                if (!addChunk(chunks, new ChunkPos(currentX, currentZ), limit)) {
                    break;
                }
                tMaxX += tDeltaX;
            } else {
                // 先穿过 Z 边界，只向 Z 方向推进一个区块。
                currentZ += stepZ;
                if (!addChunk(chunks, new ChunkPos(currentX, currentZ), limit)) {
                    break;
                }
                tMaxZ += tDeltaZ;
            }
        }

        return List.copyOf(chunks);
    }

    /**
     * 规划并提交未来水平路径，由服务器级管理器在下一 Tick 开始阶段按全局预算精确加票。
     *
     * <p>本方法同时检查本 Tick（horizon=1）路径。只有当前路径中的区块已经获得 Ticket，且均已
     * 加载并进入 entity-ticking，{@link PathLoadResult#currentTickPathReady()} 才会为 true。</p>
     *
     * @param entity 申请路径的服务端实体
     * @param start 预测起点
     * @param motion 每 Tick 速度向量
     * @param horizonTicks 前探 Tick 数
     * @param priority 全局预算分配优先级
     * @return 本次提交和当前移动路径的状态快照
     */
    public static PathLoadResult requestProjectedPath(
            @Nullable Entity entity,
            @Nullable Vec3 start,
            @Nullable Vec3 motion,
            int horizonTicks,
            RVP_ChunkPathLoadManager.RequestPriority priority) {
        if (entity == null || !(entity.level() instanceof ServerLevel serverLevel)) {
            return PathLoadResult.invalidPath();
        }

        // 完整窗口只负责提前请求；本 Tick 窗口单独用于移动许可判定。
        List<ChunkPos> projectedChunks = collectSupercoverChunks(
                start, motion, horizonTicks, RVP_ChunkPathLoadManager.MAX_CHUNKS_PER_ENTITY_TICK);
        List<ChunkPos> currentTickChunks = collectSupercoverChunks(
                start, motion, 1, RVP_ChunkPathLoadManager.MAX_CHUNKS_PER_ENTITY_TICK);
        if (projectedChunks.isEmpty() || currentTickChunks.isEmpty()) {
            return PathLoadResult.invalidPath();
        }
        boolean projectedPathTruncated = !reachesProjectedEnd(start, motion, horizonTicks, projectedChunks);
        boolean currentTickPathTruncated = !reachesProjectedEnd(start, motion, 1, currentTickChunks);

        // 提交会覆盖同一实体本 Tick 较早的计划；实际新增 Ticket 在下一 ServerTick START 统一分配。
        RVP_ChunkPathLoadManager.PathRequestSnapshot request =
                RVP_ChunkPathLoadManager.submitPathRequest(
                        entity,
                        projectedChunks,
                        currentTickChunks.get(currentTickChunks.size() - 1),
                        priority);

        // 未获管理器授权的区块先返回 NOT_REQUESTED，不能仅凭当前已加载状态绕过全局预算。
        PathReadiness readiness = checkPathReadiness(currentTickChunks, chunkPos -> {
            if (!request.grantedChunks().contains(chunkPos)) {
                return ChunkReadiness.NOT_REQUESTED;
            }
            return queryChunkReadiness(serverLevel, chunkPos, Mth.floor(start.y));
        });
        // 即使已返回的连续前缀全部就绪，实际终点未被规划覆盖时也必须拒绝移动。
        if (readiness.pathReady() && currentTickPathTruncated) {
            readiness = new PathReadiness(
                    false,
                    readiness.readyChunkCount(),
                    null,
                    ChunkReadiness.PATH_TRUNCATED);
        }
        RVP_ChunkPathLoadManager.recordPathObservation(entity, readiness, priority);
        return new PathLoadResult(
                projectedChunks.size(),
                request.requestedChunkCount(),
                readiness.readyChunkCount(),
                readiness.firstUnreadyChunk(),
                readiness.firstUnreadyState(),
                request.budgetExhausted(),
                projectedPathTruncated,
                readiness.pathReady());
    }

    /** 使用普通活动弹体优先级提交路径，供不需要显式区分状态的调用方使用。 */
    public static PathLoadResult requestProjectedPath(
            @Nullable Entity entity,
            @Nullable Vec3 start,
            @Nullable Vec3 motion,
            int horizonTicks) {
        return requestProjectedPath(
                entity,
                start,
                motion,
                horizonTicks,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE);
    }

    /**
     * Checks a path using queries that do not synchronously load or generate chunks.
     *
     * <p>仅使用无同步加载副作用的查询检查路径；返回首个未加载或未进入实体 Tick 的区块。</p>
     */
    public static PathReadiness checkEntityTickingPath(
            @Nullable ServerLevel level,
            @Nullable List<ChunkPos> chunks,
            int blockY) {
        if (level == null) {
            return PathReadiness.invalidPath();
        }
        // 统一委托给纯判定器，使生产查询和单元测试共享相同的顺序及短路语义。
        return checkPathReadiness(chunks, chunkPos -> queryChunkReadiness(level, chunkPos, blockY));
    }

    /** 使用区块中心执行无加载副作用的加载及 entity-ticking 两阶段检查。 */
    private static ChunkReadiness queryChunkReadiness(ServerLevel level, ChunkPos chunkPos, int blockY) {
        // 区块中心仅作为状态查询坐标；Y 不影响 ChunkPos，但保留调用方当前高度便于阅读和调试。
        BlockPos probe = new BlockPos(
                chunkPos.getMinBlockX() + CHUNK_SIZE / 2,
                blockY,
                chunkPos.getMinBlockZ() + CHUNK_SIZE / 2);
        // hasChunkAt 不会像 getChunk(...) 一样同步加载或生成目标区块。
        if (!level.hasChunkAt(probe)) {
            return ChunkReadiness.NOT_LOADED;
        }
        // 已加载不等于可执行实体 Tick，必须继续检查 entity-ticking 状态。
        return level.isPositionEntityTicking(probe)
                ? ChunkReadiness.READY
                : ChunkReadiness.NOT_ENTITY_TICKING;
    }

    /** 判断连续规划结果是否真正覆盖预测终点；用于识别达到上限后的截断路径。 */
    static boolean reachesProjectedEnd(
            @Nullable Vec3 start,
            @Nullable Vec3 motion,
            int horizonTicks,
            @Nullable List<ChunkPos> chunks) {
        if (!isFinite(start) || !isFinite(motion) || chunks == null || chunks.isEmpty()) {
            return false;
        }
        int horizon = Math.max(1, horizonTicks);
        double endX = start.x + motion.x * horizon;
        double endZ = start.z + motion.z * horizon;
        if (!Double.isFinite(endX) || !Double.isFinite(endZ)) {
            return false;
        }
        return chunks.get(chunks.size() - 1).equals(chunkAt(endX, endZ));
    }

    /**
     * 按路径顺序执行可替换的就绪查询，并在首个失败区块处立即短路。
     * 测试通过注入纯查询函数验证顺序，无需构造或加载 {@link ServerLevel}。
     */
    static PathReadiness checkPathReadiness(
            @Nullable List<ChunkPos> chunks,
            Function<ChunkPos, ChunkReadiness> readinessQuery) {
        // 空路径无法证明移动安全，必须按 INVALID_PATH 拒绝。
        if (chunks == null || chunks.isEmpty()) {
            return PathReadiness.invalidPath();
        }

        int readyChunkCount = 0;
        for (ChunkPos chunk : chunks) {
            // 严格从起点向终点查询，确保 firstUnreadyChunk 是运动方向上的第一个失败点。
            ChunkReadiness readiness = readinessQuery.apply(chunk);
            if (readiness != ChunkReadiness.READY) {
                // 防御异常查询器返回 null；按未加载处理，绝不误判为可移动。
                ChunkReadiness failure = readiness == null ? ChunkReadiness.NOT_LOADED : readiness;
                return new PathReadiness(false, readyChunkCount, chunk, failure);
            }
            readyChunkCount++;
        }
        return new PathReadiness(true, readyChunkCount, null, ChunkReadiness.READY);
    }

    /** 检查向量及其三个分量是否都存在且为有限数。 */
    private static boolean isFinite(@Nullable Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    /** 使用先 floor 再右移的 Minecraft 语义换算区块，保证负坐标边界正确。 */
    private static ChunkPos chunkAt(double x, double z) {
        return new ChunkPos(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    /** 计算从线段起点首次抵达当前轴下一条区块边界的归一化参数时间。 */
    private static double initialBoundaryTime(double start, double delta, int chunk, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? (chunk + 1) * (double) CHUNK_SIZE : chunk * (double) CHUNK_SIZE;
        return (boundary - start) / delta;
    }

    /** 使用相对误差比较两个边界时间，稳定识别浮点计算中的角点相交。 */
    private static boolean nearlyEqual(double first, double second) {
        if (!Double.isFinite(first) || !Double.isFinite(second)) {
            return first == second;
        }
        double scale = Math.max(1.0D, Math.max(Math.abs(first), Math.abs(second)));
        return Math.abs(first - second) <= CORNER_EPSILON * scale;
    }

    /**
     * 按顺序加入区块并去重；达到上限时返回 false，让调用方停止推进并保留连续前缀。
     */
    private static boolean addChunk(List<ChunkPos> chunks, ChunkPos chunk, int limit) {
        if (chunks.contains(chunk)) {
            return true;
        }
        if (chunks.size() >= limit) {
            return false;
        }
        chunks.add(chunk);
        return true;
    }

    /** 单个区块相对于实体 Tick 的就绪状态。 */
    public enum ChunkReadiness {
        /** 区块已加载且允许实体执行 Tick。 */
        READY,
        /** 区块当前未加载。 */
        NOT_LOADED,
        /** 区块已加载，但尚未晋级到 entity-ticking。 */
        NOT_ENTITY_TICKING,
        /** 路径已提交，但该区块尚未获得全局预算授权的 Ticket。 */
        NOT_REQUESTED,
        /** 路径达到单实体区块上限，连续前缀尚未覆盖实际预测终点。 */
        PATH_TRUNCATED,
        /** 路径本身为空或服务器世界无效。 */
        INVALID_PATH
    }

    /**
     * 路径就绪判定结果。
     *
     * @param pathReady 路径中的全部区块是否都允许实体 Tick
     * @param readyChunkCount 从起点开始连续就绪的区块数量
     * @param firstUnreadyChunk 沿运动方向遇到的第一个未就绪区块；全部就绪或路径无效时为空
     * @param firstUnreadyState 首个未就绪区块的原因；全部就绪时为 READY，路径无效时为 INVALID_PATH
     */
    public record PathReadiness(
            boolean pathReady,
            int readyChunkCount,
            @Nullable ChunkPos firstUnreadyChunk,
            ChunkReadiness firstUnreadyState) {
        /** 构造拒绝移动的无效路径结果。 */
        private static PathReadiness invalidPath() {
            return new PathReadiness(false, 0, null, ChunkReadiness.INVALID_PATH);
        }
    }

    /**
     * 路径提交和本 Tick 移动许可的合并结果。
     *
     * @param plannedChunkCount 完整前探窗口规划的区块数
     * @param requestedChunkCount 当前已获得 Ticket 的连续路径前缀长度
     * @param readyChunkCount 本 Tick 路径从起点开始连续就绪的区块数
     * @param firstUnreadyChunk 本 Tick 路径中的首个未就绪区块
     * @param firstUnreadyState 首个未就绪区块的原因
     * @param budgetExhausted 上一次全局分配是否因预算不足留下未授权区块
     * @param projectedPathTruncated 完整前探窗口是否因单实体上限而截断
     * @param currentTickPathReady 本 Tick 路径是否已全部获得 Ticket 并进入 entity-ticking
     */
    public record PathLoadResult(
            int plannedChunkCount,
            int requestedChunkCount,
            int readyChunkCount,
            @Nullable ChunkPos firstUnreadyChunk,
            ChunkReadiness firstUnreadyState,
            boolean budgetExhausted,
            boolean projectedPathTruncated,
            boolean currentTickPathReady) {
        /** 构造拒绝移动的无效路径结果。 */
        private static PathLoadResult invalidPath() {
            return new PathLoadResult(
                    0, 0, 0, null, ChunkReadiness.INVALID_PATH, false, false, false);
        }
    }
}
