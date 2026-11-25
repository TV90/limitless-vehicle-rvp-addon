package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ywzj.rvp.client.state.RVP_ClientOpticalState.displayedConfidence;

class RVP_OpticalHudConfidenceTest {
    @Test
    void distanceZoomAndVisibilityChangeTheDisplayedScore() {
        float distant = displayedConfidence(1f, 24, 18, 900);
        float nearer = displayedConfidence(1f, 48, 36, 900);
        float zoomed = displayedConfidence(1f, 96, 72, 900);
        assertTrue(distant < nearer);
        assertTrue(nearer < zoomed);
        assertEquals(1f, zoomed);
        assertEquals(0.4f * zoomed, displayedConfidence(0.4f, 96, 72, 900));
        assertEquals(nearer, displayedConfidence(1f, 96, 72, 1800));
    }
}
