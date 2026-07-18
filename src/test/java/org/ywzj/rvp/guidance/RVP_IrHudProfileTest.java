package org.ywzj.rvp.guidance;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_Range;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_IrHudProfileTest {

    @Test
    void derivesAirGroundAndMixedProfilesFromAltitudeUnion() {
        assertEquals(RVP_IrHudProfile.AIR, RVP_IrHudProfile.resolve(
                RVP_Range.of(RVP_Range.interval(25f, null))));
        assertEquals(RVP_IrHudProfile.GROUND, RVP_IrHudProfile.resolve(
                RVP_Range.of(RVP_Range.interval(null, 25f))));
        assertEquals(RVP_IrHudProfile.MIXED, RVP_IrHudProfile.resolve(
                RVP_Range.of(
                        RVP_Range.interval(null, 10f),
                        RVP_Range.interval(30f, null))));
    }
}
