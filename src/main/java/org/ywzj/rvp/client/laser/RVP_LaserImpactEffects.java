package org.ywzj.rvp.client.laser;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.effects.RVP_ProjectileParticleEffects;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Impact sparks at laser hit: flame + white smoke (MCH flak spread).
 */
public final class RVP_LaserImpactEffects {

    private static final int SPAWN_INTERVAL_TICKS = 2;
    private static final Map<LaserBeamKey, Long> LAST_SPAWN_TICK = new ConcurrentHashMap<>();

    private RVP_LaserImpactEffects() {}

    public static void spawnImpact(Level level, LaserBeamKey key, RVP_LaserBeam beam, RVP_WeaponData data) {
        if (!level.isClientSide() || !beam.hitSomething() || !beam.isDrawable()) {
            return;
        }

        long gameTime = level.getGameTime();
        Long last = LAST_SPAWN_TICK.get(key);
        if (last != null && gameTime - last < SPAWN_INTERVAL_TICKS) {
            return;
        }
        LAST_SPAWN_TICK.put(key, gameTime);

        Vec3 hit = beam.impactPoint();
        RVP_EffectsData effects = data.getEffectsData();
        float diff = effects.getFlakParticlesDiff();
        int smokeCount = Math.max(effects.getNumParticlesFlak(), 2);

        for (int i = 0; i < 2; i++) {
            double ox = (level.random.nextDouble() - 0.5) * 0.15;
            double oz = (level.random.nextDouble() - 0.5) * 0.15;
            level.addParticle(ParticleTypes.FLAME, true,
                    hit.x + ox, hit.y + 0.05, hit.z + oz,
                    0.0, 0.02 + level.random.nextDouble() * 0.02, 0.0);
        }

        for (int i = 0; i < smokeCount; i++) {
            double px = hit.x + (level.random.nextFloat() - 0.5);
            double py = hit.y + 0.1;
            double pz = hit.z + (level.random.nextFloat() - 0.5);
            double vx = (diff * 0.5) * level.random.nextGaussian();
            double vz = (diff * 0.5) * level.random.nextGaussian();
            double vy = diff * Math.abs(level.random.nextGaussian());
            level.addParticle(ParticleTypes.CLOUD, true, px, py, pz, vx, vy, vz);
        }

        if (beam.blockHit() != null && effects.isDefaultBlockImpact()) {
            RVP_ProjectileParticleEffects.spawnBlockImpactClient(level, beam.blockHit(), effects, 0.6f);
        }
    }

    public static void clearKey(LaserBeamKey key) {
        LAST_SPAWN_TICK.remove(key);
    }
}
