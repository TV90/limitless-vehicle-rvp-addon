package org.ywzj.rvp.client.visual;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验 RVP 内置爆炸音效事件与 OGG 资源之间的映射关系。 */
class RVP_DefaultExplosionSoundResourceTest {

    /** RVP 内置资源命名空间的根目录。 */
    private static final Path RESOURCE_ROOT =
            Path.of("src", "main", "resources", "assets", "ywzj_rvp");

    @Test
    void newExplosionVariantsAreRegisteredInExpectedSoundEvents() throws IOException {
        JsonObject sounds = readSounds();

        assertEventContains(sounds, "mchr_explosion_cannon_near", List.of(
                "ywzj_rvp:defaultexplosion/cannon_exp/near/cannon_def_explode_near1",
                "ywzj_rvp:defaultexplosion/cannon_exp/near/cannon_def_explode_near2",
                "ywzj_rvp:defaultexplosion/cannon_exp/near/cannon_def_explode_near3",
                "ywzj_rvp:defaultexplosion/cannon_exp/near/cannon_def_explode_near4"));
        assertEventContains(sounds, "mchr_explosion_missile_far", List.of(
                "ywzj_rvp:defaultexplosion/missile_exp/far/missile_def_explode_distant_4",
                "ywzj_rvp:defaultexplosion/missile_exp/far/missile_def_explode_distant_5",
                "ywzj_rvp:defaultexplosion/missile_exp/far/missile_def_explode_distant_6",
                "ywzj_rvp:defaultexplosion/missile_exp/far/missile_def_explode_distant_7",
                "ywzj_rvp:defaultexplosion/missile_exp/far/missile_def_explode_distant_8",
                "ywzj_rvp:defaultexplosion/missile_exp/far/missile_def_explode_distant_9"));
        assertEventContains(sounds, "vehicle_explosion_near", List.of(
                "ywzj_rvp:defaultexplosion/vehicle_exp/near/vehicle_explosion_near1",
                "ywzj_rvp:defaultexplosion/vehicle_exp/near/vehicle_explosion_near2",
                "ywzj_rvp:defaultexplosion/vehicle_exp/near/vehicle_explosion_near3",
                "ywzj_rvp:defaultexplosion/vehicle_exp/near/vehicle_explosion_near4"));
        assertEventContains(sounds, "vehicle_explosion_far", List.of(
                "ywzj_rvp:defaultexplosion/vehicle_exp/far/vehicle_explosion_far1",
                "ywzj_rvp:defaultexplosion/vehicle_exp/far/vehicle_explosion_far2",
                "ywzj_rvp:defaultexplosion/vehicle_exp/far/vehicle_explosion_far3"));
        assertEventContains(sounds, "vehicle_ammo_burst", List.of(
                "ywzj_rvp:defaultexplosion/vehicle_exp/burst/ammo_burst-001",
                "ywzj_rvp:defaultexplosion/vehicle_exp/burst/ammo_burst-002",
                "ywzj_rvp:defaultexplosion/vehicle_exp/burst/ammo_burst-003"));
        // 殉燃弹药爆响改为与燃烧声同一套客户端本地循环，只引用近音文件。
        assertEventContains(sounds, "vehicle_ammo_pops", List.of(
                "ywzj_rvp:defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-001",
                "ywzj_rvp:defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-002",
                "ywzj_rvp:defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-003",
                "ywzj_rvp:defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-004"));
    }

    @Test
    void everySoundJsonReferencePointsToNonEmptyOggAndNoBackupFile() throws IOException {
        JsonObject sounds = readSounds();
        Set<String> referencedPaths = new HashSet<>();

        for (var eventEntry : sounds.entrySet()) {
            JsonObject event = eventEntry.getValue().getAsJsonObject();
            JsonArray soundArray = event.getAsJsonArray("sounds");
            assertNotNull(soundArray, eventEntry.getKey());
            for (JsonElement soundElement : soundArray) {
                String resourceName = soundElement.isJsonObject()
                        ? soundElement.getAsJsonObject().get("name").getAsString()
                        : soundElement.getAsString();
                assertFalse(resourceName.endsWith(".bak"), resourceName);
                referencedPaths.add(resourceName);
            }
        }

        for (String resourceName : referencedPaths) {
            String path = resourceName.substring(resourceName.indexOf(':') + 1);
            Path soundPath = RESOURCE_ROOT.resolve("sounds").resolve(path + ".ogg");
            assertTrue(Files.isRegularFile(soundPath), soundPath.toString());
            assertTrue(Files.size(soundPath) > 0L, soundPath.toString());
        }
    }

    /** 读取 RVP 内置声音事件表。 */
    private static JsonObject readSounds() throws IOException {
        Path soundsPath = RESOURCE_ROOT.resolve("sounds.json");
        return JsonParser.parseString(Files.readString(soundsPath)).getAsJsonObject();
    }

    /** 断言一个声音事件包含全部指定资源路径。 */
    private static void assertEventContains(JsonObject sounds, String eventName,
                                            List<String> expectedResources) {
        JsonObject event = sounds.getAsJsonObject(eventName);
        assertNotNull(event, eventName);
        JsonArray soundArray = event.getAsJsonArray("sounds");
        assertNotNull(soundArray, eventName);

        List<String> actualResources = new ArrayList<>();
        for (JsonElement soundElement : soundArray) {
            actualResources.add(soundElement.isJsonObject()
                    ? soundElement.getAsJsonObject().get("name").getAsString()
                    : soundElement.getAsString());
        }
        for (String expectedResource : expectedResources) {
            assertTrue(actualResources.contains(expectedResource),
                    eventName + " missing " + expectedResource);
        }
    }
}
