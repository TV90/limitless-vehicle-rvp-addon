package org.ywzj.rvp.guidance;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Geometry helpers retained outside the removed stage-based steering engine. */
public final class RVP_GuidanceMath {

    private RVP_GuidanceMath() {}

    public static boolean isTargetPassAltFilter(Entity entity, float lockMinHeight) {
        // 调用虚拟目标地形开关，兼容锁定入口也不得绕回原版高度图查询。
        if (org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileTargets.ignoresTerrainHeight(entity)) return true;
        if (entity == null || lockMinHeight == 0) {
            return true;
        }
        // 非虚拟目标也可能刚卸载，导引头不能通过兼容高度门重新加载它的区块。
        if (!entity.level().hasChunkAt(entity.blockPosition())) return false;
        int groundY = entity.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                entity.getBlockX(), entity.getBlockZ());
        double agl = entity.getY() - groundY;
        return lockMinHeight > 0 ? agl >= lockMinHeight : agl <= -lockMinHeight;
    }

    public static boolean isEntityNearGroundBlocks(Entity entity, int blocksBelow) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        // 调用虚拟目标地形开关，近炸等兼容路径不沿虚拟坐标向下读取方块。
        if (org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileTargets.ignoresTerrainHeight(entity)) return false;
        if (entity.onGround()) {
            return true;
        }
        if (blocksBelow <= 0) {
            return false;
        }
        Level level = entity.level();
        int x = Mth.floor(entity.getX() + 0.5);
        int y = Mth.floor(entity.getY() + 0.5);
        int z = Mth.floor(entity.getZ() + 0.5);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < blocksBelow; i++) {
            pos.set(x, y - i, z);
            if (!level.getBlockState(pos).isAir()) {
                return true;
            }
        }
        return false;
    }
}
