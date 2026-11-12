package org.ywzj.rvp.weapon;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.List;

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

    @Test
    void averageLaunchPointsMergesCubeOriginsBeforeSolve() {
        AimContext left = context(new Vec3(-3, 10, 0), 0f, 0f);
        AimContext right = context(new Vec3(3, 10, 2), 2f, 0f);

        AimContext average = RVP_RocketBallistics.averageContexts(List.of(left, right));

        assertEquals(0.0D, average.from.x, 1.0E-9D);
        assertEquals(10.0D, average.from.y, 1.0E-9D);
        assertEquals(1.0D, average.from.z, 1.0E-9D);
        assertEquals(1.0f, average.direction.x, 1.0E-6f);
    }

    @Test
    void averageLaunchPointsKeepsYawContinuousAcrossHalfTurn() {
        AimContext first = context(Vec3.ZERO, 0f, 179f);
        AimContext second = context(Vec3.ZERO, 0f, -179f);

        AimContext average = RVP_RocketBallistics.averageContexts(List.of(first, second));

        assertEquals(180.0f, Math.abs(average.direction.y), 1.0E-4f);
    }

    private static AimContext context(Vec3 from, float xRot, float yRot) {
        AimContext context = new AimContext();
        context.from = from;
        context.direction = new Vec2(xRot, yRot);
        return context;
    }
}
