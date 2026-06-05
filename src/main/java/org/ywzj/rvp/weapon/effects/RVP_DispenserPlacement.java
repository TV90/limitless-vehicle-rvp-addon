package org.ywzj.rvp.weapon.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_DispenserPayloadData;
import org.ywzj.rvp.weapon.util.RVP_DispenserSpreadUtil;

import java.util.List;

/**
 * Applies dispenser payload items on block surfaces (MCH {@code MCH_EntityDispensedItem} parity).
 */
public final class RVP_DispenserPlacement {

    private RVP_DispenserPlacement() {}

    public static int placeAtHit(ServerLevel level, BlockHitResult hit, RVP_DispenserPayloadData payload,
                                 @Nullable Entity shooter) {
        if (!payload.hasItem() || !payload.isPlaceOnImpact()) {
            return 0;
        }
        Item item = resolveItem(payload);
        if (item == null) {
            return 0;
        }
        ServerPlayer actor = resolveActor(level, shooter);
        BlockPos anchor = RVP_DispenserSurfaceResolver.resolveAnchor(level, hit.getLocation(), hit, payload);
        return applySpread(level, anchor, hit.getLocation(), payload, item, actor);
    }

    public static int placeAtPosition(ServerLevel level, Vec3 pos, RVP_DispenserPayloadData payload,
                                      @Nullable Entity shooter, @Nullable BlockHitResult recentHit) {
        if (!payload.hasItem() || !payload.isPlaceOnImpact()) {
            return 0;
        }
        Item item = resolveItem(payload);
        if (item == null) {
            return 0;
        }
        ServerPlayer actor = resolveActor(level, shooter);
        BlockPos anchor = RVP_DispenserSurfaceResolver.resolveAnchor(level, pos, recentHit, payload);
        return applySpread(level, anchor, pos, payload, item, actor);
    }

    private static int applySpread(ServerLevel level, BlockPos anchor, Vec3 impactPos, RVP_DispenserPayloadData payload,
                                   Item item, ServerPlayer actor) {
        RandomSource random = level.getRandom();
        int damage = payload.getDamage();
        List<RVP_DispenserSpreadUtil.SpreadOffset> offsets =
                RVP_DispenserSpreadUtil.sampleOffsets(payload, random);

        int verticalSearch = payload.getSpreadRadius() + payload.resolveYRadius() + 16;
        int placed = 0;

        for (RVP_DispenserSpreadUtil.SpreadOffset offset : offsets) {
            BlockPos target = resolveTargetPos(level, anchor, offset, payload, verticalSearch);
            if (target == null || !RVP_DispenserSpreadUtil.isWithinWorld(level, target)) {
                continue;
            }
            if (!RVP_DispenserSurfaceResolver.isSolidSupport(level, target)) {
                continue;
            }
            if (payload.isSurfaceOnly() && !RVP_DispenserSurfaceResolver.hasPassableAbove(level, target)) {
                continue;
            }
            if (RVP_DispenserItemApplicator.apply(level, target, item, damage, actor, random)) {
                placed++;
            }
        }

        if (placed == 0 && payload.isSurfaceOnly()) {
            BlockPos fallback = RVP_DispenserSurfaceResolver.findSurfaceColumn(
                    level, anchor.getX(), anchor.getZ(), anchor.getY(), verticalSearch, 8, true);
            if (fallback != null
                    && RVP_DispenserItemApplicator.apply(level, fallback, item, damage, actor, random)) {
                placed = 1;
            }
        }

        return placed;
    }

    @Nullable
    private static BlockPos resolveTargetPos(ServerLevel level, BlockPos anchor,
                                             RVP_DispenserSpreadUtil.SpreadOffset offset,
                                             RVP_DispenserPayloadData payload, int verticalSearch) {
        int worldX = anchor.getX() + offset.x();
        int worldZ = anchor.getZ() + offset.z();

        if (payload.isSurfaceOnly()) {
            int hintY = anchor.getY() + offset.y();
            return RVP_DispenserSurfaceResolver.findSurfaceColumn(
                    level, worldX, worldZ, hintY, verticalSearch, 8, true);
        }

        BlockPos direct = anchor.offset(offset.x(), offset.y(), offset.z());
        if (RVP_DispenserSurfaceResolver.isSolidSupport(level, direct)) {
            return direct;
        }
        return RVP_DispenserSurfaceResolver.findSurfaceColumn(
                level, worldX, worldZ, direct.getY(), verticalSearch, 8, false);
    }

    @Nullable
    private static Item resolveItem(RVP_DispenserPayloadData payload) {
        ResourceLocation id = payload.itemId();
        if (id == null) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(id);
    }

    private static ServerPlayer resolveActor(ServerLevel level, @Nullable Entity shooter) {
        if (shooter instanceof ServerPlayer serverPlayer) {
            return serverPlayer;
        }
        if (shooter instanceof Player player) {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(player.getUUID());
            if (sp != null) {
                return sp;
            }
        }
        ServerPlayer fake = FakePlayerFactory.getMinecraft(level);
        fake.getAbilities().mayBuild = true;
        fake.getAbilities().instabuild = true;
        return fake;
    }
}
