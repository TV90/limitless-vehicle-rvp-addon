package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.state.RVP_ClientOpticalState.Caption;
import org.ywzj.rvp.client.state.RVP_ClientOpticalState.ScreenBox;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ywzj.rvp.client.state.RVP_ClientOpticalState.displayedConfidence;

class RVP_OpticalHudConfidenceTest {
    @Test
    void overlappingCaptionsKeepTheStrongestAndIndependentLabels() {
        var low = new Caption(new ScreenBox(15, 0, 60, 9), "TANK", 0, 0.42f);
        var strongest = new Caption(new ScreenBox(34, 0, 94, 9), "Highest score", 0, 0.91f);
        var tied = new Caption(new ScreenBox(35, 0, 90, 9), "Tied score", 0, 0.91f);
        var adjacent = new Caption(new ScreenBox(94, 0, 120, 9), "CAR", 0, 0.38f);
        var secondRow = new Caption(new ScreenBox(34, 9, 94, 18), "STAT", 0, 0.45f);

        assertEquals(List.of(strongest, secondRow, adjacent),
                RVP_ClientOpticalState.visibleCaptions(List.of(low, strongest, tied, adjacent, secondRow)));
    }

    @Test
    void distanceZoomAndVisibilityChangeTheDisplayedScore() {
        float distant = displayedConfidence(1f, 32, 24, 900);
        float nearer = displayedConfidence(1f, 48, 36, 900);
        float zoomed = displayedConfidence(1f, 96, 72, 900);
        assertTrue(distant < nearer);
        assertTrue(nearer < zoomed);
        assertEquals(1f, zoomed);
        // 同一目标的尺寸惩罚上限由 0.2 翻倍到 0.4。
        assertEquals(0f, displayedConfidence(1f, 20, 16, 900));
        assertEquals(0.6f, displayedConfidence(1f, 80, 25, 1000));
        assertEquals((1f - 0.8f) * 2, 1f - displayedConfidence(1f, 80, 25, 1000), 0.0001f);
        assertEquals(0.4f * zoomed, displayedConfidence(0.4f, 96, 72, 900));
        assertEquals(nearer, displayedConfidence(1f, 96, 72, 1800));
    }
}
