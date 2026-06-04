package org.ywzj.rvp.weapon.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;

/**
 * MCH {@link mcheli.weapon.MCH_EntityBaseBullet#spawnBlockPar} / {@code flakParticles*} parity for RVP projectiles.
 */
public final class RVP_ProjectileParticleEffects {

    private static final double VIEW_DISTANCE = 512.0D;
    private static final double VIEW_DISTANCE_SQ = VIEW_DISTANCE * VIEW_DISTANCE;

    private RVP_ProjectileParticleEffects() {}

    /**
     * Block-hit sparks: block crack particles + white smoke ({@link ParticleTypes#CLOUD}).
     */
    public static void spawnBlockImpact(ServerLevel level, BlockHitResult result, RVP_EffectsData effects, float entityWidth) {
        if (effects == null || effects.isImpactDisabled()) {
            return;
        }
        if (!effects.isDefaultBlockImpact()) {
            spawnCustomImpact(level, result.getLocation(), effects.getImpactParticle());
            return;
        }

        Vec3 hit = result.getLocation();
        BlockPos blockPos = result.getBlockPos();
        BlockState blockState = level.getBlockState(blockPos);
        if (blockState.isAir()) {
            return;
        }

        int crackCount = effects.getFlakParticlesCrack() + level.random.nextInt(3);
        float diff = effects.getFlakParticlesDiff();
        float width = Math.max(entityWidth, 0.25f);
        int smokeCount = effects.getNumParticlesFlak();

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(hit) > VIEW_DISTANCE_SQ) {
                continue;
            }
            for (int i = 0; i < crackCount; i++) {
                double px = hit.x + (level.random.nextFloat() - 0.5D) * width;
                double py = hit.y + 0.1D;
                double pz = hit.z + (level.random.nextFloat() - 0.5D) * width;
                double vx = diff * 0.5D * level.random.nextGaussian();
                double vz = diff * 0.5D * level.random.nextGaussian();
                double vy = diff * Math.abs(level.random.nextGaussian());
                level.sendParticles(
                        player,
                        new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                        true,
                        px, py, pz,
                        1,
                        vx, vy, vz,
                        0.02D);
            }
            for (int i = 0; i < smokeCount; i++) {
                spawnMchBulletFlakSmokeServer(level, player, hit);
            }
        }
    }

    /**
     * Vanilla explosion burst ({@link ParticleTypes#EXPLOSION_EMITTER} + {@link ParticleTypes#EXPLOSION}).
     */
    public static void spawnExplosion(ServerLevel level, Vec3 pos, RVP_EffectsData effects, float explosionRadius) {
        if (effects == null || effects.isExplosionDisabled()) {
            return;
        }
        ParticleOptions primary = resolveExplosionPrimary(effects.getExplosionParticle());
        if (primary == null) {
            return;
        }

        int burst = Mth.clamp(Math.round(explosionRadius * 1.5F), 4, 12);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) > VIEW_DISTANCE_SQ) {
                continue;
            }
            level.sendParticles(player, primary, true, pos.x, pos.y, pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            if (effects.isDefaultVanillaExplosion()) {
                level.sendParticles(player, ParticleTypes.EXPLOSION, true,
                        pos.x, pos.y, pos.z, burst, 0.4D, 0.2D, 0.4D, 0.05D);
            }
        }
    }

    /**
     * Client-side MCH block impact (laser / local preview).
     */
    public static void spawnBlockImpactClient(Level level, BlockHitResult result, RVP_EffectsData effects, float entityWidth) {
        if (!level.isClientSide() || effects == null || effects.isImpactDisabled()) {
            return;
        }
        if (!effects.isDefaultBlockImpact()) {
            return;
        }
        Vec3 hit = result.getLocation();
        BlockState blockState = level.getBlockState(result.getBlockPos());
        if (blockState.isAir()) {
            return;
        }
        RandomSource random = level.random;
        int crackCount = effects.getFlakParticlesCrack() + random.nextInt(3);
        float diff = effects.getFlakParticlesDiff();
        float width = Math.max(entityWidth, 0.25f);
        int smokeCount = effects.getNumParticlesFlak();

        for (int i = 0; i < crackCount; i++) {
            double px = hit.x + (random.nextFloat() - 0.5D) * width;
            double py = hit.y + 0.1D;
            double pz = hit.z + (random.nextFloat() - 0.5D) * width;
            double vx = diff * 0.5D * random.nextGaussian();
            double vz = diff * 0.5D * random.nextGaussian();
            double vy = diff * Math.abs(random.nextGaussian());
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                    px, py, pz, vx, vy, vz);
        }
        for (int i = 0; i < smokeCount; i++) {
            spawnMchBulletFlakSmokeClient(level, hit, random);
        }
    }

    /**
     * MCH {@link mcheli.weapon.MCH_WeaponLaser#spawnBlockPar}: cloud + dark smoke + flame per {@code num_particles_flak}.
     */
    public static void spawnLaserBlockImpactClient(Level level, BlockHitResult result, RVP_EffectsData effects) {
        if (!level.isClientSide() || effects == null || effects.isImpactDisabled()) {
            return;
        }
        Vec3 hit = result.getLocation();
        float diff = effects.getFlakParticlesDiff();
        RandomSource random = level.random;
        int count = effects.getNumParticlesFlak();

        for (int i = 0; i < count; i++) {
            double px = hit.x + (random.nextFloat() - 0.5D);
            double py = hit.y + 0.1D;
            double pz = hit.z + (random.nextFloat() - 0.5D);
            double vx = (diff / 2.0D) * random.nextGaussian();
            double vz = (diff / 2.0D) * random.nextGaussian();
            double vy = diff * Math.abs(random.nextGaussian());

            level.addParticle(ParticleTypes.CLOUD, true, px, py, pz, vx, vy, vz);
            level.addParticle(ParticleTypes.SMOKE, true, px, py, pz, vx * 0.8D, vy * 0.7D, vz * 0.8D);
            level.addParticle(ParticleTypes.FLAME, true, px, py, pz, vx * 0.4D, vy * 0.5D + 0.02D, vz * 0.4D);
        }
    }

    /** MCH {@link mcheli.weapon.MCH_EntityBaseBullet#spawnBlockPar} white smoke ({@code EntityCloudFX}). */
    private static void spawnMchBulletFlakSmokeServer(ServerLevel level, ServerPlayer player, Vec3 hit) {
        RandomSource random = level.random;
        double px = hit.x + random.nextGaussian();
        double py = hit.y + random.nextGaussian();
        double pz = hit.z + random.nextGaussian();
        double vx = random.nextGaussian() / 200.0D;
        double vy = random.nextGaussian() / 200.0D;
        double vz = random.nextGaussian() / 200.0D;
        level.sendParticles(player, ParticleTypes.CLOUD, true, px, py, pz, 1, vx, vy, vz, 0.01D);
    }

    private static void spawnMchBulletFlakSmokeClient(Level level, Vec3 hit, RandomSource random) {
        double px = hit.x + random.nextGaussian();
        double py = hit.y + random.nextGaussian();
        double pz = hit.z + random.nextGaussian();
        double vx = random.nextGaussian() / 200.0D;
        double vy = random.nextGaussian() / 200.0D;
        double vz = random.nextGaussian() / 200.0D;
        level.addParticle(ParticleTypes.CLOUD, px, py, pz, vx, vy, vz);
    }

    private static void spawnCustomImpact(ServerLevel level, Vec3 hit, String particleId) {
        ParticleOptions particle = resolveNamed(particleId);
        if (particle == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(hit) > VIEW_DISTANCE_SQ) {
                continue;
            }
            level.sendParticles(player, particle, true, hit.x, hit.y, hit.z, 8, 0.25D, 0.25D, 0.25D, 0.1D);
        }
    }

    private static ParticleOptions resolveExplosionPrimary(String id) {
        if (id == null || id.isBlank() || isNone(id)) {
            return null;
        }
        if (isDefaultExplosionId(id)) {
            return ParticleTypes.EXPLOSION_EMITTER;
        }
        return resolveNamed(id);
    }

    private static ParticleOptions resolveNamed(String id) {
        if (id == null || id.isBlank() || isNone(id)) {
            return null;
        }
        return switch (id) {
            case "flame", "minecraft:flame" -> ParticleTypes.FLAME;
            case "large_smoke", "minecraft:large_smoke" -> ParticleTypes.LARGE_SMOKE;
            case "cloud", "minecraft:cloud" -> ParticleTypes.CLOUD;
            case "smoke", "minecraft:smoke" -> ParticleTypes.SMOKE;
            case "explosion", "minecraft:explosion" -> ParticleTypes.EXPLOSION;
            case "explosion_emitter", "minecraft:explosion_emitter" -> ParticleTypes.EXPLOSION_EMITTER;
            case "campfire_smoke", "minecraft:campfire_cosy_smoke" -> ParticleTypes.CAMPFIRE_COSY_SMOKE;
            default -> null;
        };
    }

    private static boolean isNone(String id) {
        return "none".equalsIgnoreCase(id) || "minecraft:none".equalsIgnoreCase(id);
    }

    private static boolean isDefaultExplosionId(String id) {
        return id.isBlank()
                || "explosion".equalsIgnoreCase(id)
                || "minecraft:explosion".equalsIgnoreCase(id)
                || "explosion_emitter".equalsIgnoreCase(id)
                || "minecraft:explosion_emitter".equalsIgnoreCase(id);
    }
}
