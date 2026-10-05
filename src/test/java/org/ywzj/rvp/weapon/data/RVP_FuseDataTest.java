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

    @Test
    void proximityAmmoRadiusFactorDefaultsToOneAndOnlyEnlarges() {
        // 缺键 = 1.0（全弹零变化）；与 proximity_radius 解析互不干扰
        RVP_FuseData defaults = gson.fromJson("{}", RVP_FuseData.class);
        RVP_FuseData configured = gson.fromJson("""
                {
                  "proximity_radius": 5,
                  "proximity_radius_ammo_factor": 3.0
                }
                """, RVP_FuseData.class);
        // 仅支持放大：写入 <1 按 1.0 生效（探测盒第一遍查询保持旧版载具行为逐位一致的设计约束）
        RVP_FuseData shrunk = gson.fromJson("""
                {"proximity_radius_ammo_factor": 0.5}
                """, RVP_FuseData.class);

        assertEquals(1.0f, defaults.getProximityRadiusAmmoFactor());
        assertEquals(5f, configured.getProximityRadius());
        assertEquals(3.0f, configured.getProximityRadiusAmmoFactor());
        assertEquals(1.0f, shrunk.getProximityRadiusAmmoFactor());
    }
}
