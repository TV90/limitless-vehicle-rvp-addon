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
    /** 场景的水查询结果。 */ private FluidState water;
    /** 场景的干燥查询结果。 */ private FluidState air;
    /** 仅作为生产区块驻留统计的身份键，不运行服务器循环或线程。 */ private net.minecraft.server.MinecraftServer serverIdentity;

    /** 服务器构造需要磁盘存档和线程；测试用 environment 分配，仅提供下面显式实现的查询。 */
    private RVP_ProjectileSimulationLevel() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }

    /** 建立一个只用于数值查询的环境，真实弹体仍由真实构造器创建。 */
    public static RVP_ProjectileSimulationLevel create(double groundY, Double waterY) {
        RVP_ProjectileSimulationBootstrap.initialize();
        var level = RVP_ProjectileSimulationBootstrap.environment(RVP_ProjectileSimulationLevel.class);
        level.groundY = groundY; level.waterY = waterY;
        level.water = Fluids.WATER.defaultFluidState(); level.air = Fluids.EMPTY.defaultFluidState();
        level.serverIdentity = RVP_ProjectileSimulationBootstrap.environment(net.minecraft.server.dedicated.DedicatedServer.class);
        RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.level.Level.class, level, "dimension", net.minecraft.world.level.Level.OVERWORLD);
        return level;
    }

    /** 测试时序由逐刻调度提供，不用实际墙钟时间。 */
    public void observeTick(int tick) { observationTick = tick; }
    /** 给完整生产 Tick 的只读驻留统计一个稳定身份。 */ @Override public net.minecraft.server.MinecraftServer getServer() { return serverIdentity; }
    /** 测试环境全部区块已就绪，不引入真实区块载入延迟。 */ @Override public boolean hasChunkAt(BlockPos position) { return true; }
    /** 返回本刻观测时间。 */ @Override public long getGameTime() { return observationTick; }
    /** 不模拟区块等待；这个边界不会改变实体运动公式。 */ @Override public boolean isPositionEntityTicking(BlockPos position) { return true; }
    /** 生产制导离地高度查询使用同一个 groundY。 */ @Override public int getHeight(Heightmap.Types type, int x, int z) { return (int) Math.floor(groundY); }
    /** 场景允许超出原版建筑高度的飞行，地形仍是同一平面。 */ @Override public int getMinBuildHeight() { return (int) Math.floor(groundY) - 64; }
    /** 按场景水面回答生产鱼雷的“上方仍是水”检查。 */
    @Override public FluidState getFluidState(BlockPos position) {
        return waterY != null && position.getY() >= groundY && position.getY() < waterY ? water : air;
    }
    /** 环境没有玩家，不产生网络或音效接收者。 */ @Override public List<net.minecraft.server.level.ServerPlayer> players() { return List.of(); }

}
