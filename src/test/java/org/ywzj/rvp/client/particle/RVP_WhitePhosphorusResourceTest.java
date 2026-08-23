package org.ywzj.rvp.client.particle;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_WhitePhosphorusResourceTest {

    @Test
    void whitePhosphorusUsesOnlyAuthorityTextureUnderModAssets() throws IOException {
        Path texture = Path.of("src/main/resources/assets/ywzj_rvp/textures/nuclear/particle_base.png");
        assertTrue(Files.isRegularFile(texture));
        assertFalse(Files.exists(Path.of("src/main/resources/assets/rvp/particles/white_phosphorus.json")));
        assertFalse(Files.exists(Path.of("src/main/resources/assets/minecraft/atlases/particles.json")));

        BufferedImage image = ImageIO.read(texture.toFile());
        assertNotNull(image);
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
        assertEquals(0, (image.getRGB(0, 0) >>> 24) & 0xFF);
    }
}
