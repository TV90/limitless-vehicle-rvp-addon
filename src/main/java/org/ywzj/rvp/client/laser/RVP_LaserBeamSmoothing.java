package org.ywzj.rvp.client.laser;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Light temporal blend so beam endpoints do not step once per tick when the turret slews.
 */
public final class RVP_LaserBeamSmoothing {

    private static final Map<LaserBeamKey, Vec3> LAST_START = new ConcurrentHashMap<>();
    private static final Map<LaserBeamKey, Vec3> LAST_END = new ConcurrentHashMap<>();

    private RVP_LaserBeamSmoothing() {}

    public static RVP_LaserBeam forRender(LaserBeamKey key, RVP_LaserBeam beam, float partialTick) {
        Vec3 targetStart = beam.renderStart();
        Vec3 targetEnd = beam.renderEnd();

        Vec3 prevStart = LAST_START.get(key);
        Vec3 prevEnd = LAST_END.get(key);
        if (prevStart == null || prevEnd == null) {
            remember(key, targetStart, targetEnd);
            return beam;
        }

        float blend = Mth.clamp(0.55F + partialTick * 0.35F, 0.55F, 0.95F);
        Vec3 start = prevStart.lerp(targetStart, blend);
        Vec3 end = prevEnd.lerp(targetEnd, blend);
        Vec3 impact = beam.impactPoint();
        if (beam.hitSomething() && prevEnd != null) {
            impact = prevEnd.lerp(beam.impactPoint(), blend);
        }

        remember(key, start, end);
        return new RVP_LaserBeam(beam.muzzle(), start, end, impact,
                beam.hitSomething(), beam.hitEntity(), beam.blockHit());
    }

    public static void clear(LaserBeamKey key) {
        LAST_START.remove(key);
        LAST_END.remove(key);
    }

    private static void remember(LaserBeamKey key, Vec3 start, Vec3 end) {
        LAST_START.put(key, start);
        LAST_END.put(key, end);
    }
}
