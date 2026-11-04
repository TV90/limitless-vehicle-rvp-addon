package org.ywzj.rvp.entity.projectile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/** 数值试验的环境查询：固定平地、可选水平水面、已就绪区块；不承担弹体积分。 */
public final class RVP_ProjectileSimulationLevel extends ServerLevel {
    /** 平地世界高度，格。 */ private double groundY;
    /** 可选水面世界高度；null 表示无水。 */ private Double waterY;
    /** 当前已发生的世界刻，供生产采样与时序方法读取。 */ private long observationTick;
    /** 无地形依赖的飞行回归可拒绝所有高度图查询，避免测试环境掩盖虚拟段的强加载。 */ private boolean terrainQueriesForbidden;
    /** 场景的水查询结果。 */ private FluidState water;
    /** 场景的干燥查询结果。 */ private FluidState air;
    /** 仅作为生产区块驻留统计的身份键，不运行服务器循环或线程。 */ private net.minecraft.server.MinecraftServer serverIdentity;

    /** 测试世界的内存 SavedData 存储；不调用保存，不写入任何真实世界目录。 */
    private net.minecraft.world.level.storage.DimensionDataStorage dataStorage;
    /** 测试显式加入的观察者索引，使用原版实体查询结构。 */
    private net.minecraft.world.level.entity.EntityLookup<net.minecraft.world.entity.Entity> entityLookup;
    /** 原版实体查询器；虚拟目标仍不加入此索引。 */
    private net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> entityGetter;
    /** 仅提供 getChunkNow 的只读区块源，用于真实虚拟弹地形预检，不运行加载或区块调度。 */
    private ReadOnlyChunkSource chunkSource;

    /** 服务器构造需要磁盘存档和线程；测试用 environment 分配，仅提供下面显式实现的查询。 */
    private RVP_ProjectileSimulationLevel() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }

    /** 建立一个只用于数值查询的环境，真实弹体仍由真实构造器创建。 */
    public static RVP_ProjectileSimulationLevel create(double groundY, Double waterY) {
        RVP_ProjectileSimulationBootstrap.initialize();
        var level = RVP_ProjectileSimulationBootstrap.environment(RVP_ProjectileSimulationLevel.class);
        level.groundY = groundY; level.waterY = waterY;
        level.water = Fluids.WATER.defaultFluidState(); level.air = Fluids.EMPTY.defaultFluidState();
        level.serverIdentity = RVP_ProjectileSimulationBootstrap.environment(net.minecraft.server.dedicated.DedicatedServer.class);
        // 原版碰撞查询会记性能计数；测试不采样，但仍执行原查询而不是返回伪造候选。
        RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.level.Level.class, level, "profiler",
                (java.util.function.Supplier<net.minecraft.util.profiling.ProfilerFiller>)
                        () -> net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
        // 原版世界查询会额外枚举多部件实体；固定平地环境没有末影龙或其它分体。
        RVP_ProjectileSimulationBootstrap.write(ServerLevel.class, level, "dragonParts",
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
        level.dataStorage = new net.minecraft.world.level.storage.DimensionDataStorage(
                new java.io.File("build/projectile-simulation-environment/unsaved-data"), null);
        level.entityLookup = new net.minecraft.world.level.entity.EntityLookup<>();
        level.entityGetter = new net.minecraft.world.level.entity.LevelEntityGetterAdapter<>(level.entityLookup,
                new net.minecraft.world.level.entity.EntitySectionStorage<>(net.minecraft.world.entity.Entity.class,
                        ignored -> net.minecraft.world.level.entity.Visibility.HIDDEN));
        RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.level.Level.class, level, "dimension", net.minecraft.world.level.Level.OVERWORLD);
        level.chunkSource = RVP_ProjectileSimulationBootstrap.environment(ReadOnlyChunkSource.class);
        level.chunkSource.view = RVP_ProjectileSimulationBootstrap.environment(ReadOnlyChunk.class);
        level.chunkSource.view.world = level;
        return level;
    }

    /** 测试时序由逐刻调度提供，不用实际墙钟时间。 */
    public void observeTick(int tick) { observationTick = tick; }
    /** 仅为测试收紧环境边界，不替换任何弹体计算。 */
    public void forbidTerrainQueries() { terrainQueriesForbidden = true; }
    /** 测试只登记观察者，不触发世界 Tick、区块票据或网络生成包。 */
    public void addObserver(net.minecraft.world.entity.Entity entity) { entityLookup.add(entity); }
    /** 观察者不参加区块调度，但光学遮挡等局部查询必须看见同一实体表。 */
    @Override public java.util.List<net.minecraft.world.entity.Entity> getEntities(
            net.minecraft.world.entity.Entity excluded, net.minecraft.world.phys.AABB box,
            java.util.function.Predicate<? super net.minecraft.world.entity.Entity> filter) {
        java.util.List<net.minecraft.world.entity.Entity> result = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : entityLookup.getAllEntities()) {
            if (entity != excluded && entity.getBoundingBox().intersects(box) && filter.test(entity)) result.add(entity);
        }
        return result;
    }
    /** 提供原版已加载实体查询边界，目标选择与交接逻辑仍执行生产实现。 */
    @Override public net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> getEntities() { return entityGetter; }
    /** 按会话 ID 查询已加载观察者，虚拟记录不在这里。 */
    @Override public net.minecraft.world.entity.Entity getEntity(int entityId) { return entityLookup.getEntity(entityId); }
    /** 按稳定 UUID 查询已加载观察者。 */
    @Override public net.minecraft.world.entity.Entity getEntity(java.util.UUID uuid) { return entityLookup.getEntity(uuid); }
    /** 虚拟记录使用真实 SavedData 容器，仅隔离磁盘与服务器线程。 */
    @Override public net.minecraft.world.level.storage.DimensionDataStorage getDataStorage() { return dataStorage; }
    /** 给完整生产 Tick 的只读驻留统计一个稳定身份。 */ @Override public net.minecraft.server.MinecraftServer getServer() { return serverIdentity; }
    /** 测试环境全部区块已就绪，不引入真实区块载入延迟。 */ @Override public boolean hasChunkAt(BlockPos position) { return true; }
    /** 数值环境地形全部已加载；生产视线裁剪可以读取固定平地，不启动真实区块源。 */
    @Override public boolean isLoaded(BlockPos position) { return true; }
    /** 数值世界只允许查询已有视图，不为虚拟段预检启动真实区块源。 */
    @Override public net.minecraft.server.level.ServerChunkCache getChunkSource() { return chunkSource; }
    /** 返回本刻观测时间。 */ @Override public long getGameTime() { return observationTick; }
    /** 不模拟区块等待；这个边界不会改变实体运动公式。 */ @Override public boolean isPositionEntityTicking(BlockPos position) { return true; }
    /** 生产制导离地高度查询使用同一个 groundY；无地形依赖的测试不得调用。 */
    @Override public int getHeight(Heightmap.Types type, int x, int z) {
        if (terrainQueriesForbidden) throw new AssertionError("无地形依赖的飞行不应查询高度图");
        return (int) Math.floor(groundY);
    }
    /** 场景覆盖较大的竖直范围；不依赖真实维度注册表，也不改写物理高度值。 */
    @Override public int getHeight() { return 32768; }
    /** 场景允许超出原版建筑高度的飞行，地形仍是同一平面。 */ @Override public int getMinBuildHeight() { return (int) Math.floor(groundY) - 64; }
    /** 引信只查询固定平面的空气/实心状态，不加载地形或改写近炸判定。 */
    @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos position) {
        return (position.getY() < groundY ? net.minecraft.world.level.block.Blocks.STONE
                : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState();
    }
    /** 按场景水面回答生产鱼雷的“上方仍是水”检查。 */
    @Override public FluidState getFluidState(BlockPos position) {
        return waterY != null && position.getY() >= groundY && position.getY() < waterY ? water : air;
    }

    /** 不复制地形公式，只把现有固定平地环境暴露给生产 getChunkNow 查询。 */
    private static final class ReadOnlyChunkSource extends net.minecraft.server.level.ServerChunkCache {
        /** 所有场景坐标共用的只读视图，实际位置仍由原环境方法判断。 */ private ReadOnlyChunk view;
        private ReadOnlyChunkSource() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
        @Override public net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x, int z) { return view; }
        @Override public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z,
                net.minecraft.world.level.chunk.ChunkStatus status, boolean create) {
            throw new AssertionError("数值环境不得执行同步区块加载");
        }
    }

    /** 地形、流体读数来自同一个场景，不替换射线或任何弹体计算。 */
    private static final class ReadOnlyChunk extends net.minecraft.world.level.chunk.LevelChunk {
        /** 提供平地和水面观测的原数值环境。 */ private RVP_ProjectileSimulationLevel world;
        private ReadOnlyChunk() { super(null, new net.minecraft.world.level.ChunkPos(0, 0)); }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) { return world.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return world.getFluidState(pos); }
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos,
                net.minecraft.world.level.chunk.LevelChunk.EntityCreationType creation) { return null; }
    }
    /** 环境没有玩家，不产生网络或音效接收者。 */ @Override public List<net.minecraft.server.level.ServerPlayer> players() { return List.of(); }

}
