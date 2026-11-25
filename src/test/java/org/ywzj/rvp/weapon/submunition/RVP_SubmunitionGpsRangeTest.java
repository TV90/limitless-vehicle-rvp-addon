package org.ywzj.rvp.weapon.submunition;

import com.google.gson.Gson;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionData;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SubmunitionGpsRangeTest {
    private static final Gson GSON = new Gson();
    private static final Vec3 GPS_POINT = new Vec3(100, 64, -24);

    @Test
    void configuredGateUsesThreeDimensionalDistanceAndAcceptsBoundary() {
        RVP_SubmunitionRunner runner = runner("""
                {"gps_release_max_distance":80,"releases":[{"triggers":["on_fuse"]}]}
                """);
        assertTrue(runner.gpsRangeAllows(GPS_POINT, GPS_POINT.add(48, 64, 0)));
        assertFalse(runner.gpsRangeAllows(GPS_POINT, GPS_POINT.add(48, 64, 1)));
        assertFalse(runner.gpsRangeAllows(GPS_POINT, GPS_POINT.add(0, 81, 0)));
    }

    @Test
    void configuredGateRequiresGpsButUngatedRoundsKeepExistingBehavior() {
        assertFalse(runner("""
                {"gps_release_max_distance":80,"releases":[{}]}
                """).gpsRangeAllows(null, Vec3.ZERO));
        assertTrue(runner("""
                {"releases":[{}]}
                """).gpsRangeAllows(null, Vec3.ZERO));
        assertTrue(runner("""
                {"gps_release_max_distance":0,"releases":[{}]}
                """).gpsRangeAllows(null, Vec3.ZERO));
    }

    private static RVP_SubmunitionRunner runner(String json) {
        return RVP_SubmunitionRunner.create(GSON.fromJson(json, RVP_SubmunitionData.class), 0);
    }
}
