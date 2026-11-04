package org.ywzj.rvp.virtualflight.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实体素射线跨过未知区块时仍检查已知地形，任何加载分支都会直接令测试失败。 */
class RVP_VirtualMissileWorldAccessTest {
    /** 独立执行本类也先准备原版注册表，不能依赖其它测试的运行顺序。 */
    @org.junit.jupiter.api.BeforeAll
    static void initialize() { RVP_ProjectileSimulationBootstrap.initialize(); }

    @Test
    void loadedIslandDetectsStoneAndWaterWithoutQueryingMissingChunksThroughLoadingApis() {
        var context = RVP_ProjectileSimulationLevel.create(0, null);
        var missile = new Marker(EntityType.MARKER, context);
        var level = RVP_ProjectileSimulationBootstrap.environment(LoadedOnlyLevel.class);
        level.source = RVP_ProjectileSimulationBootstrap.environment(LoadedOnlySource.class);
        level.source.chunks = new HashMap<>();
        var chunk = RVP_ProjectileSimulationBootstrap.environment(LoadedOnlyChunk.class);
        chunk.obstacle = new BlockPos(24, 10, 0);
        chunk.block = Blocks.STONE.defaultBlockState();
        level.source.chunks.put(new ChunkPos(1, 0).toLong(), chunk);
        Vec3 from = new Vec3(0.5, 10.5, 0.5), to = new Vec3(48.5, 10.5, 0.5);

        assertFalse(level.hasChunkAt(BlockPos.containing(from)));
        assertFalse(level.hasChunkAt(BlockPos.containing(to)));
        assertTrue(RVP_VirtualMissileWorldAccess.hasKnownObstacle(level, missile, from, to),
                "两端都未知也不能漏掉中间已加载的墙");
        chunk.block = Blocks.WATER.defaultBlockState();
        // 独立数值环境没有执行完整游戏 Bootstrap；用原版初始化建立水方块真实的流体缓存。
        chunk.block.initCache();
        assertFalse(chunk.block.getFluidState().isEmpty(), "原版水方块必须提供实际流体状态");
        assertTrue(RVP_VirtualMissileWorldAccess.hasKnownObstacle(level, missile, from, to),
                "必须在进入已知水体前恢复，虚拟段不模拟入水运动");
        // 导引头视线不以水体当作方块遮挡；共用视图仍执行调用者原有的流体策略。
        assertEquals(net.minecraft.world.phys.HitResult.Type.MISS,
                RVP_VirtualMissileWorldAccess.loadedTerrain(level).clip(new net.minecraft.world.level.ClipContext(
                        from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, missile)).getType());
        chunk.block = Blocks.AIR.defaultBlockState();
        assertFalse(RVP_VirtualMissileWorldAccess.hasKnownObstacle(level, missile, from, to));
        assertTrue(level.source.nonLoadingReads > 0);

        int reads = level.source.nonLoadingReads;
        assertTrue(RVP_VirtualMissileWorldAccess.hasKnownObstacle(level, missile, from, from.add(8192, 0, 0)),
                "超过预检预算直接暂停恢复，不能无限遍历方块");
        assertEquals(reads, level.source.nonLoadingReads);
    }

    /** 只给出加载现状，禁止退回世界级的方块加载入口。 */
    private static final class LoadedOnlyLevel extends ServerLevel {
        /** 测试区块源，仅保存明确加入的区块。 */ private LoadedOnlySource source;
        private LoadedOnlyLevel() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public ServerChunkCache getChunkSource() { return source; }
        @Override public boolean hasChunkAt(BlockPos pos) { return source.chunks.containsKey(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public BlockState getBlockState(BlockPos pos) { throw new AssertionError("不应进入 Level.getBlockState 的加载路径"); }
    }

    /** getChunkNow 可查询现状，任何同步 getChunk 都意味着实现越过边界。 */
    private static final class LoadedOnlySource extends ServerChunkCache {
        /** 已加载区块，键为原版区块坐标编码。 */ private Map<Long, LevelChunk> chunks;
        /** 非加载读取次数，确认预检实际执行了正式体素射线。 */ private int nonLoadingReads;
        private LoadedOnlySource() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
        @Override public LevelChunk getChunkNow(int x, int z) { nonLoadingReads++; return chunks.get(ChunkPos.asLong(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) { throw new AssertionError("虚拟地形预检不得加载区块"); }
    }

    /** 用原版石头和水体形状作为地形输入，不伪造射线结果。 */
    private static final class LoadedOnlyChunk extends LevelChunk {
        /** 唯一测试障碍物坐标。 */ private BlockPos obstacle;
        /** 当前障碍物原版方块状态。 */ private BlockState block;
        private LoadedOnlyChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public BlockState getBlockState(BlockPos pos) { return pos.equals(obstacle) ? block : Blocks.AIR.defaultBlockState(); }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    }
}
