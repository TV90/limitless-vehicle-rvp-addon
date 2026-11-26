package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.state.RVP_ClientOpticalState.Caption;
import org.ywzj.rvp.client.state.RVP_ClientOpticalState.ScreenBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ywzj.rvp.client.state.RVP_ClientOpticalState.displayedConfidence;
import static org.ywzj.rvp.guidance.RVP_OpticalTargeting.canDisplay;

class RVP_OpticalHudConfidenceTest {
    @Test
    void overlappingCaptionsStayInPlaceWithOnlyExposedPixelsVisible() {
        var low = new ScreenBox(15, 5, 60, 14);
        var higher = new Caption(new ScreenBox(34, 2, 94, 11), "High score", 0, 0.91f);
        var lowerOverlap = new Caption(new ScreenBox(22, 11, 50, 18), "Other score", 0, 0.81f);

        assertEquals(List.of(new ScreenBox(15, 5, 34, 11), new ScreenBox(15, 11, 22, 14),
                new ScreenBox(50, 11, 60, 14)),
                RVP_ClientOpticalState.exposedCaptionAreas(low, List.of(higher, lowerOverlap)));
        assertEquals(List.of(low), RVP_ClientOpticalState.exposedCaptionAreas(low, List.of()));
        assertTrue(RVP_ClientOpticalState.exposedCaptionAreas(low,
                List.of(new Caption(low, "Cover", 0, 0.99f))).isEmpty());
    }

    @Test
    void distanceZoomVisibilityAndLightUseTheSynchronizedFormula() {
        float distant = displayedConfidence(1f, 1f, 20, 6, 1000);
        float nearer = displayedConfidence(1f, 1f, 30, 4, 1000);
        float zoomed = displayedConfidence(1f, 1f, 120, 5, 1000);
        assertTrue(distant < nearer);
        assertTrue(nearer < zoomed);
        assertEquals(1f, zoomed, 0.0001f);
        assertEquals(0f, displayedConfidence(1f, 1f, 1.99, 1.99, 1000));
        assertEquals(0f, displayedConfidence(1f, 1f, 21, 0.025, 1000));
        assertEquals(0.19f, displayedConfidence(1f, 1f, 2, 2, 1000), 0.0001f);
        assertEquals(0.5287992f, nearer, 0.0001f);
        assertEquals(0.7975f, displayedConfidence(1f, 1f, 61, 5, 1000), 0.0001f);
        assertEquals(nearer, displayedConfidence(1f, 1f, 4, 30, 1000));
        assertEquals(nearer, displayedConfidence(1f, 1f, 60, 8, 2000));
        assertFalse(canDisplay(displayedConfidence(0.1f, 0.4f, 5, 5, 1000)));
        assertFalse(canDisplay(displayedConfidence(1f, 0.4f, 5, 5, 1000)));
        assertTrue(canDisplay(displayedConfidence(1f, 0.4f, 60, 5, 1000)));

        assertEquals(0.7696f, displayedConfidence(1f, 0.52f, 120, 5, 1000), 0.0001f);
        assertEquals(0.706f, displayedConfidence(0.5f, 0.52f, 120, 5, 1000), 0.0001f);
        assertEquals(0.36f, displayedConfidence(1f, 0.20f, 120, 5, 1000), 0.0001f);
        assertFalse(canDisplay(displayedConfidence(1f, 0.20f, 120, 5, 1000)));
        assertEquals(0f, displayedConfidence(0f, 1f, 120, 5, 1000));
    }

    @Test
    void worldProjectionTracksFovWithoutAnyWeaponZoomChange() {
        var wide = new RVP_ClientOpticalState.RenderProjection(
                new Matrix4f().perspective((float) Math.toRadians(75), 1.5f, 0.05f, 2000), Vec3.ZERO, 1);
        var zoomed = new RVP_ClientOpticalState.RenderProjection(
                new Matrix4f().perspective((float) Math.toRadians(25), 1.5f, 0.05f, 2000), Vec3.ZERO, 1);
        double wideWidth = 2 * (wide.project(1, 0, -80, 1500, 1000).x - 750);
        double zoomedWidth = 2 * (zoomed.project(1, 0, -80, 1500, 1000).x - 750);
        assertTrue(zoomedWidth > 3 * wideWidth);
        assertTrue(displayedConfidence(1f, 1f, zoomedWidth, 15, 1000)
                > displayedConfidence(1f, 1f, wideWidth, 15, 1000));
    }

    @Test
    void stationaryScoreChangesButStaysWithinItsDisplayBand() {
        Set<Float> perfect = new HashSet<>();
        for (int tick = 0; tick < 120; tick += 5) {
            perfect.add(RVP_ClientOpticalState.animatedConfidence(1f, 123, tick));
            var abbreviated = RVP_ClientOpticalState.animatedConfidence(0.49f, 123, tick);
            var full = RVP_ClientOpticalState.animatedConfidence(0.50f, 123, tick);
            assertTrue(abbreviated > 0.4f && abbreviated < 0.5f);
            assertTrue(full >= 0.5f);
            assertEquals(full, RVP_ClientOpticalState.animatedConfidence(0.50f, 123, tick + 4));
        }
        assertEquals(Set.of(0.97f, 0.98f, 0.99f), perfect);
    }
}
