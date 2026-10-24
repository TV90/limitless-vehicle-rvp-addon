package org.ywzj.rvp.weapon.visual;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile.HitClass.*;

class RVP_ShockwaveProfileTest {
    @Test
    void classifiesAirGroundAndBuriedWithInclusiveGroundBoundaries() {
        assertEquals(AIR, RVP_ShockwaveProfile.classify(2.5001));
        assertEquals(GROUND, RVP_ShockwaveProfile.classify(2.5));
        assertEquals(GROUND, RVP_ShockwaveProfile.classify(-1));
        assertEquals(BURIED, RVP_ShockwaveProfile.classify(-1.0001));
    }

    @Test
    void directionOrderInterpolationAndWrapAreConsistent() {
        var profile = new RVP_ShockwaveProfile(GROUND, 64, 0.7F,
                List.of(10F, 20F, 30F, 40F, 50F, 60F, 70F, 80F));
        assertEquals(10, profile.radiusAt(0));
        assertEquals(30, profile.radiusAt(Math.PI / 2), 0.0001);
        assertEquals(15, profile.radiusAt(Math.PI / 8), 0.0001);
        assertEquals(45, profile.radiusAt(-Math.PI / 8), 0.0001);
        assertEquals(profile.radiusAt(0), profile.radiusAt(Math.PI * 2));
    }

    @Test
    void profileOwnsItsSamplesAndRejectsMalformedGeometry() {
        var reach = new ArrayList<>(List.of(4F, 4F, 4F, 4F, 4F, 4F, 4F, 4F));
        var profile = new RVP_ShockwaveProfile(GROUND, 64, 0.7F, reach);
        reach.set(0, 99F);
        assertEquals(4, profile.radiusAt(0));
        assertThrows(UnsupportedOperationException.class, () -> profile.reach().set(0, 1F));
        assertThrows(IllegalArgumentException.class, () -> new RVP_ShockwaveProfile(AIR, Double.NaN, 0, reach));
        assertThrows(IllegalArgumentException.class, () -> new RVP_ShockwaveProfile(AIR, 64, 2, reach));
        assertThrows(IllegalArgumentException.class, () -> new RVP_ShockwaveProfile(AIR, 64, 0, List.of(1F)));
        reach.set(0, Float.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> new RVP_ShockwaveProfile(AIR, 64, 0, reach));
        reach.set(0, -1F);
        assertThrows(IllegalArgumentException.class, () -> new RVP_ShockwaveProfile(AIR, 64, 0, reach));
    }

    @Test
    void sizeHandlesDisabledAndExtremeConfigurationWithoutOverflow() {
        assertEquals(0, RVP_ShockwaveProfile.visualRadius(10, 0));
        assertEquals(4, RVP_ShockwaveProfile.visualRadius(0, 1));
        assertEquals(50, RVP_ShockwaveProfile.visualRadius(10, 2));
        assertEquals(RVP_ShockwaveProfile.MAX_RADIUS,
                RVP_ShockwaveProfile.visualRadius(Float.MAX_VALUE, Float.MAX_VALUE));
    }
}
