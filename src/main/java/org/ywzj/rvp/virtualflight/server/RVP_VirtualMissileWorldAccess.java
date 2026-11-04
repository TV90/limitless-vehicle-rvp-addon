package org.ywzj.rvp.virtualflight.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 虚拟导弹读取已加载世界的边界；只查询现状，不申请区块、不把未知地形当成空气。 */
public final class RVP_VirtualMissileWorldAccess {
    /** 单次地形预检的最大长度，单位格；超长段直接请求实体恢复，避免无界体素遍历。 */
    private static final double MAX_PROBE_DISTANCE = 4096;
    private RVP_VirtualMissileWorldAccess() { }

    /**
     * 仅确认已知障碍或水体是否要求恢复；false 只表示没有查到已知障碍，不代表未知地形为空气。
     * 缺失区块之间仍检查已有区块，防止高速导弹越过中间已加载的障碍后才发现需要恢复。
     */
    public static boolean hasKnownObstacle(Level level, Entity missile, Vec3 start, Vec3 end) {
        if (start.distanceToSqr(end) > MAX_PROBE_DISTANCE * MAX_PROBE_DISTANCE) return true;
        ClipContext context = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, missile);
        if (!(level instanceof ServerLevel serverLevel)) return false;
        // 调用共用只读视图，碰撞预检与虚拟导引头的遮挡查询都不能加载相邻区块。
        return loadedTerrain(serverLevel).clip(context).getType() != HitResult.Type.MISS;
    }

    /** 只暴露已存在地形的视图，调用方自行解释缺失地形，不得把射线 MISS 当作全路径已确认。 */
    public static BlockGetter loadedTerrain(ServerLevel level) {
        // 即使中心路径完整也使用此视图：形状会查询相邻块，不能从旁边偷偷加载新区块。
        // 所有访问都经 getChunkNow，原版射线与方块/流体形状计算保持不变。
        return new BlockGetter() {
            private LevelChunk chunkAt(BlockPos position) {
                return level.isOutsideBuildHeight(position) ? null
                        : level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
            }
            @Override public BlockState getBlockState(BlockPos position) {
                LevelChunk chunk = chunkAt(position);
                return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(position);
            }
            @Override public FluidState getFluidState(BlockPos position) {
                LevelChunk chunk = chunkAt(position);
                return chunk == null ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()
                        : chunk.getFluidState(position);
            }
            @Override public BlockEntity getBlockEntity(BlockPos position) {
                LevelChunk chunk = chunkAt(position);
                return chunk == null ? null : chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
            }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
        };
    }
}
