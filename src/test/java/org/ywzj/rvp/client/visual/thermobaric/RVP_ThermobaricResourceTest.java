package org.ywzj.rvp.client.visual.thermobaric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricResourceTest {
    /** 测试运行目录下的 RVP 客户端资源根目录。 */
    private static final Path RESOURCE_ROOT =
            Path.of("src", "main", "resources", "assets", "rvp");

    @Test
    void standardPresetAndSoundDefinitionsExist() throws IOException {
        Path presetPath = RESOURCE_ROOT.resolve(
                Path.of("visual_effects", "thermobaric_standard.json"));
        Path soundsPath = RESOURCE_ROOT.resolve("sounds.json");

        JsonObject preset = JsonParser.parseString(Files.readString(presetPath)).getAsJsonObject();
        JsonObject sounds = JsonParser.parseString(Files.readString(soundsPath)).getAsJsonObject();
        assertEquals("rvp:thermobaric_near", preset.get("near_sound").getAsString());
        assertTrue(sounds.has("thermobaric_near"));
        assertTrue(sounds.has("thermobaric_far"));
        assertTrue(sounds.has("thermobaric_tail"));
    }

    @Test
    void allThreeOggAssetsArePresentAndNonEmpty() throws IOException {
        for (String fileName : new String[]{"near.ogg", "far.ogg", "tail.ogg"}) {
            Path soundPath = RESOURCE_ROOT.resolve(
                    Path.of("sounds", "thermobaric", fileName));
            assertTrue(Files.isRegularFile(soundPath), fileName);
            assertTrue(Files.size(soundPath) > 0L, fileName);
        }
    }
}
