package org.ywzj.rvp.firesupport.delivery;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportProblemCollector;

import static org.junit.jupiter.api.Assertions.*;

class RVP_VerticalProjectileDeliveryTest {
    @Test
    void zeroJitterIsExactlyDownwardAndNonzeroJitterIsDeterministicBoundedCone() {
        assertEquals(new Vec3(0, -1, 0), RVP_VerticalProjectileDelivery.resolveEntryDirection(1, 0, 0));
        Vec3 first = RVP_VerticalProjectileDelivery.resolveEntryDirection(1234, 7, 12);
        Vec3 again = RVP_VerticalProjectileDelivery.resolveEntryDirection(1234, 7, 12);
        assertEquals(first, again);
        assertEquals(1.0, first.length(), 1e-12);
        double tiltDegrees = Math.toDegrees(Math.acos(-first.y));
        assertTrue(tiltDegrees <= 12.0 + 1e-9);
        assertNotEquals(first, RVP_VerticalProjectileDelivery.resolveEntryDirection(1234, 8, 12));
    }

    @Test
    void deliveryFactoryCreatesTypedRuntimeDelivery() {
        var parsed = new RVP_FireSupportDeliveryTypes.VerticalProjectileData(120, 0, 10, 0);
        var delivery = RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.VERTICAL_PROJECTILE)
                .create(parsed);
        assertInstanceOf(RVP_VerticalProjectileDelivery.class, delivery);
        assertEquals(RVP_FireSupportDeliveryTypes.VERTICAL_PROJECTILE, delivery.typeId());
    }

    @Test
    void omittedEntrySpeedUsesWeaponMuzzleSpeedSentinel() {
        RVP_FireSupportProblemCollector problems = new RVP_FireSupportProblemCollector();
        Object parsed = RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.VERTICAL_PROJECTILE)
                .parse(new JsonObject(), problems, "delivery_data");
        assertFalse(problems.hasProblems());
        var data = assertInstanceOf(RVP_FireSupportDeliveryTypes.VerticalProjectileData.class, parsed);
        assertEquals(0.0, data.entrySpeedMetersPerTick());
        assertEquals(120.0, data.spawnHeightAboveImpactMeters());
        assertEquals(10, data.preloadTicks());
    }
}
