package org.ywzj.rvp.client.laser;

import net.minecraft.world.level.Level;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.effects.RVP_ProjectileParticleEffects;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Impact sparks at laser block hit ({@link mcheli.weapon.MCH_WeaponLaser#spawnBlockPar}).
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

        RVP_EffectsData effects = data.getEffectsData();
        if (beam.blockHit() != null && !effects.isImpactDisabled()) {
            RVP_ProjectileParticleEffects.spawnLaserBlockImpactClient(level, beam.blockHit(), effects);
        }
    }

    public static void clearKey(LaserBeamKey key) {
        LAST_SPAWN_TICK.remove(key);
    }
}
