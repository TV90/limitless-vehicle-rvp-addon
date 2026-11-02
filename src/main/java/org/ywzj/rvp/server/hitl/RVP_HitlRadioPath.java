package org.ywzj.rvp.server.hitl;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 无加载副作用的无线电遮挡检查；明确区分缺失地形与实际无遮挡。 */
public final class RVP_HitlRadioPath {
    /** 三态检测结果；UNKNOWN 不冒充已经验证的 CLEAR。 */
    public enum Result { CLEAR, BLOCKED, UNKNOWN }
    private RVP_HitlRadioPath() {}

    /**
     * 沿原版体素遍历访问已有 LevelChunk；绝不调用 Level.getBlockState/getChunk 的加载分支。
     * 每条射线受控制距离服务器上限约束，调用方还按武器配置降低检查频率。
     */
    public static Result check(ServerLevel level, Entity missile, Vec3 from, Vec3 to) {
        if (!finite(from) || !finite(to)) return Result.UNKNOWN;
        ClipContext context = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, missile);
        boolean[] missing = { false };
        // 方块形状也可能查询相邻坐标，必须跨区块读取真实邻块且保持无加载副作用。
        BlockGetter loaded = new BlockGetter() {
            private LevelChunk chunkAt(BlockPos pos) {
                if (level.isOutsideBuildHeight(pos)) return null;
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) missing[0] = true;
                return chunk;
            }
            @Override public BlockState getBlockState(BlockPos pos) {
                LevelChunk chunk = chunkAt(pos);
                return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) {
                LevelChunk chunk = chunkAt(pos);
                return chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
        };
        Result result = BlockGetter.traverseBlocks(from, to, context, (ray, position) -> {
            if (level.isOutsideBuildHeight(position)) return null;
            LevelChunk chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
            if (chunk == null) {
                missing[0] = true;
                return null;
            }
            BlockState block = chunk.getBlockState(position);
            var shape = ray.getBlockShape(block, loaded, position);
            HitResult hit = shape.clip(from, to, position);
            return hit != null && hit.getType() == HitResult.Type.BLOCK ? Result.BLOCKED : null;
        }, ray -> Result.CLEAR);
        return result == Result.BLOCKED ? result : missing[0] ? Result.UNKNOWN : Result.CLEAR;
    }

    /** 是否因当前检测结果暂停无线电控制；缺失地形策略由武器显式配置。 */
    public static boolean blocks(Result result, boolean requireLoadedPath) {
        return result == Result.BLOCKED || requireLoadedPath && result == Result.UNKNOWN;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
