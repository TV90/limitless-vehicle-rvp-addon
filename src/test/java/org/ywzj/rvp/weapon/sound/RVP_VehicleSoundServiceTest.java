package org.ywzj.rvp.weapon.sound;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import static org.junit.jupiter.api.Assertions.*;

/** 显式配置门与远距离去重，不再测试已移除的载具/口径分类。 */
class RVP_VehicleSoundServiceTest {
    @BeforeAll static void bootstrap() { RVP_ProjectileSimulationBootstrap.initialize(); }
    @Test void unconfiguredMissilesRocketsAndLargeCalibersNeverGetDefaultSounds() {
        for(String kind:new String[]{"missile","rocket","bomb"}) {
            var data=GsonUtil.GSON.fromJson("{\"type\":\"rvp:"+kind+"\",\"caliber\":1000}",RVP_WeaponData.class);
            assertNull(RVP_VehicleSoundService.configuredSound(data));
        }
        assertNull(RVP_VehicleSoundService.configuredSound(new Object()));
        assertNull(RVP_VehicleSoundService.resolveConfiguredSound(null));
    }
    @Test void arbitraryWeaponCanUseItsOwnDeclaredSoundAndDistanceBand() {
        var data=GsonUtil.GSON.fromJson("""
          {"type":"rvp:bomb","fire_sound_data":{"sound":"example:launch_near","far_sound":"example:launch_far",
           "far_distance":137.5,"max_distance":950,"volume":2.5,"pitch":0.85}}
          """,RVP_WeaponData.class);
        var config=RVP_VehicleSoundService.configuredSound(data); assertNotNull(config);
        assertEquals("example:launch_near",config.soundAt(137.5).toString());
        assertEquals("example:launch_far",config.soundAt(137.6).toString());
        assertEquals(950,config.getMaxDistance()); assertEquals(2.5F,config.getVolume()); assertEquals(.85F,config.getPitch());
    }
    @Test void invalidOrIncompleteConfigFallsBackWithoutTakingOver() {
        for(String config:new String[]{"{}","{\"sound\":\"example:test\"}",
                "{\"sound\":\"example:test\",\"max_distance\":-1}",
                "{\"sound\":\"example:test\",\"max_distance\":100,\"far_sound\":\"example:far\",\"far_distance\":101}"}) {
            var data=GsonUtil.GSON.fromJson("{\"fire_sound_data\":"+config+"}",RVP_WeaponData.class);
            assertNull(RVP_VehicleSoundService.configuredSound(data));
        }
    }
    @Test void fallbackOnlyCoversListenersOutsideActualTrackingRange() {
        assertEquals(512,RVP_VehicleSoundService.trackingRangeBlocks(32));
        assertFalse(RVP_VehicleSoundService.needsFallback(512D*512,512));
        assertTrue(RVP_VehicleSoundService.needsFallback(512.01D*512.01,512));
        assertEquals(16,RVP_VehicleSoundService.trackingRangeBlocks(0));
        assertEquals(160,RVP_VehicleSoundService.effectiveTrackingBlocks(32,10));
    }
}
