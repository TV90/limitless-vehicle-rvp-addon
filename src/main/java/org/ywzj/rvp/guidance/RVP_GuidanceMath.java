package org.ywzj.rvp.guidance;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Geometry helpers retained outside the removed stage-based steering engine. */
public final class RVP_GuidanceMath {

    private RVP_GuidanceMath() {}

    public static boolean isTargetPassAltFilter(Entity entity, float lockMinHeight) {
        if (entity == null || lockMinHeight == 0) {
            return true;
        }
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
