package org.ywzj.rvp.weapon.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_DispenserPayloadData;

/**
 * Resolves ground/surface anchors for dispenser spreads (aerial fuse, ring, sparse density).
 */
public final class RVP_DispenserSurfaceResolver {

    private static final int DEFAULT_DOWN_SEARCH = 96;
    private static final int DEFAULT_UP_SEARCH = 8;

    private RVP_DispenserSurfaceResolver() {}

    /**
     * Block to treat as the spread origin for horizontal offsets (usually the top solid under impact).
     */
    public static BlockPos resolveAnchor(ServerLevel level, Vec3 impactPos, @Nullable BlockHitResult blockHit,
                                         RVP_DispenserPayloadData payload) {
        if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        BlockHitResult down = level.clip(new ClipContext(
                impactPos,
                impactPos.add(0, -DEFAULT_DOWN_SEARCH, 0),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                null));
        if (down.getType() == HitResult.Type.BLOCK) {
            return down.getBlockPos();
        }
        int hintY = BlockPos.containing(impactPos).getY();
        int x = BlockPos.containing(impactPos).getX();
        int z = BlockPos.containing(impactPos).getZ();
        int search = payload.getSpreadRadius() + payload.resolveYRadius() + 16;
        BlockPos surface = findSurfaceColumn(level, x, z, hintY, search, DEFAULT_UP_SEARCH, true);
        return surface != null ? surface : BlockPos.containing(impactPos);
    }

    /**
     * Highest solid block at (x,z) with passable space above (placement target / use-on block).
     */
    @Nullable
    public static BlockPos findSurfaceColumn(ServerLevel level, int x, int z, int hintY,
                                             int searchBelow, int searchAbove, boolean requirePassableAbove) {
        int minY = Math.max(level.getMinBuildHeight(), hintY - searchBelow);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, hintY + searchAbove);
        for (int y = maxY; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isSolidSupport(level, pos) && (!requirePassableAbove || hasPassableAbove(level, pos))) {
                return pos;
            }
        }
        return null;
    }

    public static boolean isSolidSupport(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = level.getFluidState(pos);
        if (state.isAir() && fluid.isEmpty()) {
            return false;
        }
        return state.isSolid() || !fluid.isEmpty();
    }

    public static boolean hasPassableAbove(ServerLevel level, BlockPos pos) {
        BlockPos above = pos.above();
        BlockState state = level.getBlockState(above);
        FluidState fluid = level.getFluidState(above);
        return state.isAir() || state.canBeReplaced() || !fluid.isEmpty();
    }
}
