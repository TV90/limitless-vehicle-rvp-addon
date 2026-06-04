package org.ywzj.rvp.weapon.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

/**
 * Applies dispenser items with multi-face {@link Item#useOn} and direct world fallbacks.
 */
public final class RVP_DispenserItemApplicator {

    private static final Direction[] FACE_ORDER = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    private RVP_DispenserItemApplicator() {}

    public static boolean apply(ServerLevel level, BlockPos clickedPos, Item item, int damage,
                                ServerPlayer actor, RandomSource random) {
        prepareActor(actor, clickedPos, random);

        ItemStack stack = new ItemStack(item, 1);
        if (damage > 0) {
            stack.setDamageValue(damage);
        }

        if (tryUseOnFaces(level, clickedPos, item, stack, actor)) {
            return true;
        }
        if (item.use(level, actor, InteractionHand.MAIN_HAND).getResult().consumesAction()) {
            return true;
        }
        return applyDirectFallback(level, clickedPos, item);
    }

    private static void prepareActor(ServerPlayer actor, BlockPos clickedPos, RandomSource random) {
        actor.setPos(clickedPos.getX() + 0.5, clickedPos.getY() + 2.0, clickedPos.getZ() + 0.5);
        actor.setYRot(random.nextFloat() * 360.0F);
        actor.setXRot(90.0F);
        if (actor instanceof FakePlayer fake) {
            fake.getAbilities().mayBuild = true;
            fake.getAbilities().instabuild = true;
        }
    }

    private static boolean tryUseOnFaces(ServerLevel level, BlockPos clickedPos, Item item,
                                         ItemStack stack, ServerPlayer actor) {
        for (Direction face : FACE_ORDER) {
            Vec3 hitVec = Vec3.atCenterOf(clickedPos).add(
                    face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, face, clickedPos, false);
            UseOnContext context = new UseOnContext(level, actor, InteractionHand.MAIN_HAND, stack, hit);
            InteractionResult result = item.useOn(context);
            if (result.consumesAction()) {
                return true;
            }
        }
        return false;
    }

    private static boolean applyDirectFallback(ServerLevel level, BlockPos supportPos, Item item) {
        if (item == Items.FLINT_AND_STEEL) {
            BlockPos firePos = supportPos.above();
            if (level.getBlockState(firePos).isAir()) {
                return level.setBlock(firePos, Blocks.FIRE.defaultBlockState(), 3);
            }
            return false;
        }
        if (item == Items.TORCH) {
            return placeTorch(level, supportPos);
        }
        if (item instanceof BlockItem blockItem) {
            return placeBlockOnTop(level, supportPos, blockItem);
        }
        return false;
    }

    private static boolean placeTorch(ServerLevel level, BlockPos supportPos) {
        BlockPos above = supportPos.above();
        if (level.getBlockState(supportPos).isFaceSturdy(level, supportPos, Direction.UP)
                && (level.getBlockState(above).isAir() || level.getBlockState(above).canBeReplaced())) {
            return level.setBlock(above, Blocks.TORCH.defaultBlockState(), 3);
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(supportPos).isFaceSturdy(level, supportPos, dir)) {
                continue;
            }
            BlockPos torchPos = supportPos.relative(dir);
            if (!level.getBlockState(torchPos).isAir() && !level.getBlockState(torchPos).canBeReplaced()) {
                continue;
            }
            BlockState state = Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, dir.getOpposite());
            return level.setBlock(torchPos, state, 3);
        }
        return false;
    }

    private static boolean placeBlockOnTop(ServerLevel level, BlockPos supportPos, BlockItem blockItem) {
        BlockPos placePos = supportPos.above();
        BlockState placeState = blockItem.getBlock().defaultBlockState();
        if (!placeState.canSurvive(level, placePos)) {
            return false;
        }
        if (!level.getBlockState(placePos).canBeReplaced() && !level.getBlockState(placePos).isAir()) {
            return false;
        }
        return level.setBlock(placePos, placeState, 3);
    }
}
