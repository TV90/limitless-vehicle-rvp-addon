package org.ywzj.rvp.weapon;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RVP_RocketBallisticsTest {

    @Test
    void cachedTerrainStopsDescendingTrajectoryBeyondLoadedChunks() {
        Vec3 impact = RVP_RocketBallistics.intersectCachedTerrain(
                new Vec3(1000, 70, 0),
                new Vec3(1006, 58, 0),
                (x, z) -> 64);

        assertNotNull(impact);
        assertEquals(64.0D, impact.y, 1.0E-6D);
    }

    @Test
    void targetHeightPlaneRemainsFallbackWhenTerrainCacheIsMissing() {
        Vec3 impact = RVP_RocketBallistics.intersectDescendingGroundPlane(
                new Vec3(1000, 70, 0),
                new Vec3(1006, 58, 0),
                64.0D);

        assertNotNull(impact);
        assertEquals(64.0D, impact.y, 1.0E-6D);
    }
}
