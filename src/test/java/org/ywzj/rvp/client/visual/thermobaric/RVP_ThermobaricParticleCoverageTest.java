package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RVP_ThermobaricParticleCoverageTest {
    @Test
    void bundledParticleTextureHasExpectedAlphaWeightedCoverage() throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(
                "/assets/ywzj_rvp/textures/nuclear/particle_base.png")) {
            assertNotNull(inputStream);
            BufferedImage image = ImageIO.read(inputStream);
            long alphaSum = 0L;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    alphaSum += image.getRGB(x, y) >>> 24;
                }
            }

            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            assertEquals(116L * 255L, alphaSum);
            assertEquals(0.453125F, RVP_ThermobaricParticleCoverage.resolveEffectiveCoverage(
                    alphaSum, (long) image.getWidth() * image.getHeight()), 1.0E-7F);
        }
    }

    @Test
    void transparentTextureStaysZeroAndInvalidDimensionsUseFallback() {
        assertEquals(0.0F,
                RVP_ThermobaricParticleCoverage.resolveEffectiveCoverage(0L, 256L),
                1.0E-7F);
        assertEquals(RVP_ThermobaricParticleCoverage.FALLBACK_COVERAGE,
                RVP_ThermobaricParticleCoverage.resolveEffectiveCoverage(0L, 0L),
                1.0E-7F);
    }
}
