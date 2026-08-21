package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_FuseDataTest {

    private final Gson gson = new Gson();

    @Test
    void aheadDataLivesAlongsideButDoesNotEnableManualAirburst() {
        RVP_FuseData fuse = gson.fromJson("""
                {
                  "ahead_enabled": true,
                  "ahead_burst_offset_meters": 30,
                  "ahead_require_lock": false,
                  "ahead_min_ground_clearance": 20
                }
                """, RVP_FuseData.class);

        assertFalse(fuse.isProgrammableAirburst());
        assertTrue(fuse.isAheadEnabled());
        assertEquals(30f, fuse.getAheadBurstOffsetMeters());
        assertFalse(fuse.isAheadRequireLock());
        assertEquals(20f, fuse.getAheadMinGroundClearance());
    }

    @Test
    void groundProximityFuseParsesDefaultsAndClampsInvalidValues() {
        RVP_FuseData defaults = gson.fromJson("{}", RVP_FuseData.class);
        RVP_FuseData configured = gson.fromJson("""
                {
                  "ground_proximity_fuse_distance": 12.5,
                  "ground_proximity_fuse_arm_tick": 8
                }
                """, RVP_FuseData.class);
        RVP_FuseData negative = gson.fromJson("""
                {
                  "ground_proximity_fuse_distance": -4,
                  "ground_proximity_fuse_arm_tick": -3
                }
                """, RVP_FuseData.class);
        RVP_FuseData nonFinite = gson.fromJson("""
                {"ground_proximity_fuse_distance": "NaN"}
                """, RVP_FuseData.class);

        assertEquals(0f, defaults.getGroundProximityFuseDistance());
        assertEquals(0, defaults.getGroundProximityFuseArmTick());
        assertEquals(12.5f, configured.getGroundProximityFuseDistance());
        assertEquals(8, configured.getGroundProximityFuseArmTick());
        assertEquals(0f, negative.getGroundProximityFuseDistance());
        assertEquals(0, negative.getGroundProximityFuseArmTick());
        assertEquals(0f, nonFinite.getGroundProximityFuseDistance());
    }
}
