package org.ywzj.rvp.weapon.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block penetration along the flight path (destroy counted solids; pass decorative blocks like MCH).
 */
public final class RVP_WallPenetrationUtil {

    private RVP_WallPenetrationUtil() {}

    /** Pass through without consuming {@code wall_penetration} (MCH soft-block skip). */
    public static boolean isPassThroughBlock(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        Block block = state.getBlock();
        if (block instanceof LeavesBlock
                || block instanceof IronBarsBlock
                || block == Blocks.DEAD_BUSH
                || block == Blocks.COBWEB) {
            return true;
        }
        if (state.is(BlockTags.LEAVES)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
            return true;
        }
        if (state.is(Blocks.GLASS) || state.is(Blocks.TINTED_GLASS) || state.is(Blocks.GLASS_PANE)) {
            return true;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.contains("stained_glass");
    }

    /** Bedrock-like: stops penetration and triggers a normal impact. */
    public static boolean isImpenetrable(BlockState state, Level level, BlockPos pos) {
        float hardness = state.getDestroySpeed(level, pos);
        return hardness < 0f;
    }

    /**
     * Breaks the hit block when penetration applies. Returns false if the block cannot be broken.
     */
    public static boolean destroyForPenetration(Level level, BlockPos pos, BlockState state, Entity breaker) {
        if (isImpenetrable(state, level, pos)) {
            return false;
        }
        return level.destroyBlock(pos, false, breaker);
    }

    /** Nudge the projectile past a block face along its velocity. */
    public static Vec3 positionPastBlockFace(Vec3 hitLocation, Vec3 velocity, BlockHitResult result) {
        Vec3 step = velocity.lengthSqr() > 1.0E-12 ? velocity.normalize() : Vec3.atLowerCornerOf(result.getDirection().getNormal());
        Vec3 outward = Vec3.atLowerCornerOf(result.getDirection().getNormal());
        if (step.dot(outward) < 0) {
            outward = outward.scale(-1);
        }
        return hitLocation.add(step.scale(0.35)).add(outward.scale(0.08));
    }

    public static Vec3 positionPastEntityHit(Vec3 hitLocation, Vec3 velocity) {
        if (velocity.lengthSqr() <= 1.0E-12) {
            return hitLocation.add(0, 0.05, 0);
        }
        return hitLocation.add(velocity.normalize().scale(0.45));
    }

    public static Direction faceFromHit(BlockHitResult result) {
        return result.getDirection();
    }

    /**
     * Whether a solid block is hard enough for ricochet ({@code destroySpeed &gt; minHardness}).
     */
    public static boolean canBlockBounce(BlockState state, Level level, BlockPos pos, float minHardness) {
        if (state.isAir() || isPassThroughBlock(state)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0f) {
            return false;
        }
        return hardness > minHardness;
    }
}
