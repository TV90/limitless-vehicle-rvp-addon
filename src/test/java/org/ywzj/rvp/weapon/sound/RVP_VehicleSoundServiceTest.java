package org.ywzj.rvp.weapon.sound;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import static org.junit.jupiter.api.Assertions.*;

/** 显式配置门与远距离去重，不再测试已移除的载具/口径分类。 */
class RVP_VehicleSoundServiceTest {
    @BeforeAll static void bootstrap() { RVP_ProjectileSimulationBootstrap.initialize(); }
    @Test void onlyDisplaySoundsAndSettingsConfigureRemotePlayback() {
        var json=com.google.gson.JsonParser.parseString("""
          {"sounds":{"fire":"example:near","fire_far":"example:far"},
           "fire_sound":{"far_distance":137.5,"max_distance":950,"volume":2.5,"pitch":0.85}}
          """).getAsJsonObject();
        var config=org.ywzj.rvp.config.RVP_DisplayFireSound.parse(json); assertNotNull(config);
        assertEquals("example:near",config.soundAt(137.5).toString());
        assertEquals("example:far",config.soundAt(137.6).toString());
        assertEquals(950,config.getMaxDistance()); assertEquals(2.5F,config.getVolume()); assertEquals(.85F,config.getPitch());
        json.remove("fire_sound"); assertNull(org.ywzj.rvp.config.RVP_DisplayFireSound.parse(json));
        json.addProperty("caliber",1000); json.addProperty("type","rvp:missile");
        assertNull(org.ywzj.rvp.config.RVP_DisplayFireSound.parse(json));
    }
    @Test void missingNativeFireAndInvalidDistanceNeverOverrideNativeSounds() {
        for (String body:new String[]{"{}","{\"max_distance\":-1}",
                "{\"max_distance\":100,\"far_distance\":101}","{\"max_distance\":100,\"volume\":0}"}) {
            var json=com.google.gson.JsonParser.parseString("{\"sounds\":{\"fire\":\"example:near\",\"fire_far\":\"example:far\"},\"fire_sound\":"+body+"}").getAsJsonObject();
            assertNull(org.ywzj.rvp.config.RVP_DisplayFireSound.parse(json));
        }
        var json=com.google.gson.JsonParser.parseString("{\"sounds\":{},\"fire_sound\":{\"max_distance\":100}}").getAsJsonObject();
        assertNull(org.ywzj.rvp.config.RVP_DisplayFireSound.parse(json));
    }
    @Test void fallbackOnlyCoversListenersOutsideActualTrackingRange() {
        assertEquals(512,RVP_VehicleSoundService.trackingRangeBlocks(32));
        assertFalse(RVP_VehicleSoundService.needsFallback(512D*512,512));
        assertTrue(RVP_VehicleSoundService.needsFallback(512.01D*512.01,512));
        assertEquals(16,RVP_VehicleSoundService.trackingRangeBlocks(0));
        assertEquals(160,RVP_VehicleSoundService.effectiveTrackingBlocks(32,10));
    }
}
