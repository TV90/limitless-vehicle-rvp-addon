package org.ywzj.rvp.firesupport;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RVP_FireSupportPatternTest {
    @Test
    void pointIsDeterministicAndNeverLeavesScaledCircle() {
        var pattern = RVP_FireSupportTestProfiles.parse().patterns().get("point").pattern();
        for (int i = 0; i < 100; i++) {
            var context = context(i, 100, Map.of("radius_m", 18.0), 1.5, 0);
            var first = pattern.resolve(context);
            assertEquals(first, pattern.resolve(context));
            assertTrue(Math.hypot(first.x(), first.z()) <= 27.0 + 1e-9);
        }
    }

    @Test
    void lineStaysInsideRotatedRectangle() {
        var pattern = RVP_FireSupportTestProfiles.parse().patterns().get("line").pattern();
        for (int i = 0; i < 100; i++) {
            var point = pattern.resolve(context(i, 100, Map.of("length_m", 120.0, "width_m", 24.0), 1.0, 90));
            assertTrue(Math.abs(point.x()) <= 60.0 + 1e-9);
            assertTrue(Math.abs(point.z()) <= 12.0 + 1e-9);
        }
    }

    @Test
    void creepingLongitudinalProgressIsMonotonicAndBounded() {
        var pattern = RVP_FireSupportTestProfiles.parse().patterns().get("creeping").pattern();
        double previous = -1;
        for (int i = 0; i < 25; i++) {
            var point = pattern.resolve(context(i, 25, Map.of("length_m", 160.0, "width_m", 32.0, "step_m", 20.0), 1.0, 0));
            assertTrue(point.z() >= previous - 1e-9);
            assertTrue(point.z() <= 160.0 + 1e-9);
            assertTrue(Math.abs(point.x()) <= 16.0 + 1e-9);
            previous = point.z();
        }
    }

    @Test
    void builtInRegistriesAreCompleteAndImmutable() {
        assertEquals(3, RVP_FireSupportPatternTypes.all().size());
        assertEquals(1, RVP_FireSupportDeliveryTypes.all().size());
        assertThrows(UnsupportedOperationException.class, () -> RVP_FireSupportPatternTypes.all().clear());
    }

    @Test
    void dispersionMultiplierIsClampedToProfileParameterMaximum() {
        var pattern = RVP_FireSupportTestProfiles.parse().patterns().get("point").pattern();
        for (int i = 0; i < 100; i++) {
            var point = pattern.resolve(context(i, 100, Map.of("radius_m", 64.0), 1.5, 0));
            assertTrue(Math.hypot(point.x(), point.z()) <= 64.0 + 1e-9);
        }
    }

    private static RVP_FireSupportPattern.Context context(int round, int total, Map<String, Double> parameters,
                                                            double multiplier, double heading) {
        return new RVP_FireSupportPattern.Context(0, 0, heading, round, total, 123456789L, parameters, multiplier);
    }
}
