package org.ywzj.rvp.weapon.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_DetonateData;
import org.ywzj.rvp.weapon.data.RVP_EnumFluidKind;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.List;
/**
 * Applies {@link RVP_DetonateData} at an impact position on the server.
 */
public final class RVP_DetonateApplier {

    private RVP_DetonateApplier() {}

    public static void apply(ServerLevel level, Vec3 center, @Nullable BlockHitResult blockHit,
                           RVP_DetonateData detonate, @Nullable Entity owner,
                           @Nullable AbstractVehicle shooterVehicle, boolean blockImpact) {
        if (detonate == null || !detonate.hasAnyEffect()) {
            return;
        }

        BlockPos origin = resolveOrigin(center, blockHit);

        if (blockImpact && detonate.hasFire() && detonate.getFireData().isOnBlock()) {
            applyFire(level, origin, detonate.getFireData());
        }
        if (!blockImpact && detonate.hasFire() && detonate.getFireData().isOnEntity()) {
            applyFire(level, BlockPos.containing(center.x, center.y, center.z), detonate.getFireData());
        }

        if (detonate.hasPotionCloud()) {
            spawnPotionCloud(level, center, detonate.getPotionCloudData(), owner);
        }
        if (detonate.hasPotionEffect()) {
            applyDirectPotion(level, center, detonate.getPotionEffectData(), owner, shooterVehicle);
        }
        if (detonate.hasPlaceBlock()) {
            applyPlaceBlock(level, origin, detonate.getPlaceBlockData());
        }
        if (detonate.hasLightning()) {
            spawnLightning(level, center, detonate.getLightningData());
        }
        if (detonate.hasFreeze()) {
            applyFreeze(level, center, detonate.getFreezeData(), owner, shooterVehicle);
        }
        if (detonate.hasIgniteEntity()) {
            applyIgniteEntities(level, center, detonate.getIgniteEntityData(), owner, shooterVehicle);
        }
        if (detonate.hasKnockback()) {
            applyKnockback(level, center, detonate.getKnockbackData(), owner, shooterVehicle);
        }
        if (detonate.hasClearPlants()) {
            clearPlants(level, origin, detonate.getClearPlantsData());
        }
        if (detonate.hasFluid()) {
            applyFluid(level, origin, detonate.getFluidData());
        }
    }

    private static BlockPos resolveOrigin(Vec3 center, @Nullable BlockHitResult blockHit) {
        if (blockHit != null) {
            return blockHit.getBlockPos().relative(blockHit.getDirection());
        }
        return BlockPos.containing(center.x, center.y, center.z);
    }

    private static void applyFire(ServerLevel level, BlockPos origin, RVP_DetonateData.FireEffectData fire) {
        int radius = fire.getRadius();
        BlockState fireState = fire.isSoulFire() ? Blocks.SOUL_FIRE.defaultBlockState() : Blocks.FIRE.defaultBlockState();
        if (radius <= 0) {
            tryPlaceFire(level, origin, fireState, fire.getChance());
            return;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = 0; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    tryPlaceFire(level, origin.offset(dx, dy, dz), fireState, fire.getChance());
                }
            }
        }
    }

    private static void tryPlaceFire(ServerLevel level, BlockPos pos, BlockState fireState, float chance) {
        if (level.random.nextFloat() > chance) {
            return;
        }
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.isSolidRender(level, below) || belowState.isFlammable(level, below, Direction.UP)) {
            level.setBlock(pos, fireState, 3);
        }
    }

    private static void spawnPotionCloud(ServerLevel level, Vec3 center,
                                         RVP_DetonateData.PotionCloudEffectData spec, @Nullable Entity owner) {
        MobEffect effect = resolveEffect(spec.getEffect());
        if (effect == null) {
            return;
        }
        AreaEffectCloud cloud = new AreaEffectCloud(level, center.x, center.y, center.z);
        cloud.setRadius(spec.getRadius());
        cloud.setDuration(spec.getCloudDurationTicks());
        cloud.setWaitTime(0);
        if (spec.getRadiusPerTick() != 0f) {
            cloud.setRadiusPerTick(spec.getRadiusPerTick());
        }
        cloud.addEffect(new MobEffectInstance(effect, spec.getDurationTicks(), spec.getAmplifier()));
        if (owner instanceof LivingEntity livingOwner) {
            cloud.setOwner(livingOwner);
        }
        level.addFreshEntity(cloud);
    }

    private static void applyDirectPotion(ServerLevel level, Vec3 center,
                                        RVP_DetonateData.PotionCloudEffectData spec,
                                        @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        MobEffect effect = resolveEffect(spec.getEffect());
        if (effect == null) {
            return;
        }
        MobEffectInstance instance = new MobEffectInstance(effect, spec.getDurationTicks(), spec.getAmplifier());
        AABB box = new AABB(center, center).inflate(spec.getRadius());
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> matchesTarget(spec.getTargets(), e, owner, shooterVehicle));
        for (LivingEntity living : entities) {
            living.addEffect(instance);
        }
    }

    @Nullable
    private static MobEffect resolveEffect(String id) {
        ResourceLocation key = parseId(id);
        if (key == null) {
            return null;
        }
        if (!BuiltInRegistries.MOB_EFFECT.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.MOB_EFFECT.get(key);
    }

    @Nullable
    private static ResourceLocation parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.contains(":")) {
            trimmed = "minecraft:" + trimmed;
        }
        return ResourceLocation.tryParse(trimmed);
    }

    private static boolean matchesTarget(String targets, LivingEntity entity,
                                         @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        return switch (targets) {
            case "all" -> true;
            case "players" -> entity instanceof Player;
            case "hostile" -> entity.getType().getCategory() == MobCategory.MONSTER;
            case "non_allied" -> !isAllied(entity, owner, shooterVehicle);
            default -> true;
        };
    }

    private static boolean isAllied(LivingEntity entity, @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        if (owner != null && entity == owner) {
            return true;
        }
        if (shooterVehicle != null && shooterVehicle.getPassengers().contains(entity)) {
            return true;
        }
        return false;
    }

    private static void applyPlaceBlock(ServerLevel level, BlockPos origin, RVP_DetonateData.PlaceBlockEffectData spec) {
        ResourceLocation blockId = parseId(spec.getBlock());
        if (blockId == null) {
            return;
        }
        if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
            return;
        }
        var block = BuiltInRegistries.BLOCK.get(blockId);
        BlockState state = block.defaultBlockState();
        int radius = spec.getRadius();
        if (radius <= 0) {
            tryPlaceBlock(level, origin, state, spec);
            return;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                tryPlaceBlock(level, origin.offset(dx, 0, dz), state, spec);
            }
        }
    }

    private static void tryPlaceBlock(ServerLevel level, BlockPos pos, BlockState state,
                                      RVP_DetonateData.PlaceBlockEffectData spec) {
        if (level.random.nextFloat() > spec.getChance()) {
            return;
        }
        BlockState current = level.getBlockState(pos);
        if (!canReplace(current, spec.getReplaceMode())) {
            return;
        }
        level.setBlock(pos, state, 3);
    }

    private static boolean canReplace(BlockState state, String mode) {
        return switch (mode) {
            case "always" -> true;
            case "replaceable" -> state.canBeReplaced();
            default -> state.isAir();
        };
    }

    private static void spawnLightning(ServerLevel level, Vec3 center, RVP_DetonateData.LightningEffectData spec) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(center.x, center.y, center.z);
        if (!spec.dealsDamage()) {
            bolt.setVisualOnly(true);
        }
        level.addFreshEntity(bolt);
    }

    private static void applyFreeze(ServerLevel level, Vec3 center, RVP_DetonateData.FreezeEffectData spec,
                                    @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        AABB box = new AABB(center, center).inflate(spec.getRadius());
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> matchesTarget(spec.getTargets(), e, owner, shooterVehicle))) {
            living.setTicksFrozen(Math.max(living.getTicksFrozen(), spec.getFreezeTicks()));
        }
    }

    private static void applyIgniteEntities(ServerLevel level, Vec3 center, RVP_DetonateData.IgniteEntityEffectData spec,
                                            @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        AABB box = new AABB(center, center).inflate(spec.getRadius());
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> matchesTarget(spec.getTargets(), e, owner, shooterVehicle))) {
            living.setSecondsOnFire(spec.getSeconds());
        }
    }

    private static void applyKnockback(ServerLevel level, Vec3 center, RVP_DetonateData.KnockbackEffectData spec,
                                       @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        AABB box = new AABB(center, center).inflate(spec.getRadius());
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> matchesTarget(spec.getTargets(), e, owner, shooterVehicle))) {
            Vec3 offset = living.position().subtract(center);
            if (offset.lengthSqr() < 1.0E-6) {
                offset = new Vec3(0, 1, 0);
            } else {
                offset = offset.normalize();
            }
            float strength = spec.getStrength();
            living.push(offset.x * strength, 0.25 + offset.y * strength * 0.5, offset.z * strength);
        }
    }

    private static void clearPlants(ServerLevel level, BlockPos origin, RVP_DetonateData.RadiusEffectData spec) {
        int r = (int) Math.ceil(spec.getRadius());
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) {
                        continue;
                    }
                    mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = level.getBlockState(mutable);
                    if (state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.LEAVES)
                            || state.is(BlockTags.FLOWERS)) {
                        level.destroyBlock(mutable, false);
                    }
                }
            }
        }
    }

    private static void applyFluid(ServerLevel level, BlockPos origin, RVP_DetonateData.FluidEffectData spec) {
        RVP_EnumFluidKind kind = spec.getFluidType();
        if (kind == null) {
            return;
        }
        BlockState fluidState = kind == RVP_EnumFluidKind.LAVA
                ? Fluids.LAVA.defaultFluidState().createLegacyBlock()
                : Fluids.WATER.defaultFluidState().createLegacyBlock();
        int r = (int) Math.ceil(spec.getRadius());
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                BlockPos pos = origin.offset(dx, 0, dz);
                if (level.random.nextFloat() > spec.getChance()) {
                    continue;
                }
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, fluidState, 3);
                }
            }
        }
    }
}
