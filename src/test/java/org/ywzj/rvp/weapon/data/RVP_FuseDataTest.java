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
}
