package org.ywzj.rvp.client.resource.pylon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 RVP 自有挂架 display 的解析与按 id / model 双路查找。 */
class RVP_PylonDisplayTest {

    private static final ResourceLocation ID =
            new ResourceLocation("rvp", "entity/weapon_mount/weapon_mount_pl12");

    @AfterEach
    void clearCache() {
        RVP_PylonDisplayCache.replace(Map.of());
    }

    @Test
    void parsesModelAndSpecialBoneEffects() {
        JsonObject json = JsonParser.parseString("""
                {
                  "model": "rvp:entity/weapon_mount/weapon_mount_pl12",
                  "texture": "rvp:textures/entity/weapon_mount/pylon_pl12.png",
                  "special_bone_effects": [
                    {"bone": "missile1", "texture": "rvp:textures/particle/flare", "type": "muzzle_flash"}
                  ]
                }
                """).getAsJsonObject();

        RVP_PylonDisplay display = RVP_PylonDisplay.parse(ID, json);
        assertNotNull(display);
        assertEquals(ID, display.id());
        assertEquals(new ResourceLocation("rvp", "entity/weapon_mount/weapon_mount_pl12"), display.model());
        assertEquals(1, display.effects().size());
        SpecialBoneEffect effect = display.effects().get(0);
        assertEquals("missile1", effect.bone);
        assertEquals(SpecialBoneEffect.SpecialBoneEffectType.MUZZLE_FLASH, effect.type);
    }

    @Test
    void defaultsEffectsToEmpty() {
        JsonObject json = JsonParser.parseString(
                "{\"model\":\"rvp:entity/weapon_mount/weapon_mount_pl12\"}").getAsJsonObject();

        RVP_PylonDisplay display = RVP_PylonDisplay.parse(ID, json);
        assertNotNull(display);
        assertTrue(display.effects().isEmpty());
    }

    @Test
    void rejectsMissingOrInvalidModel() {
        assertNull(RVP_PylonDisplay.parse(ID, JsonParser.parseString("{}").getAsJsonObject()));
        assertNull(RVP_PylonDisplay.parse(ID,
                JsonParser.parseString("{\"model\":\"not a valid id\"}").getAsJsonObject()));
    }

    @Test
    void cacheFindsByIdThenByModelPath() {
        // 故意让 display id 与 model 路径不同，验证 model 路径兜底分支。
        ResourceLocation aliasId = new ResourceLocation("rvp", "entity/weapon_mount/weapon_mount_pl12_alias");
        RVP_PylonDisplay display = RVP_PylonDisplay.parse(aliasId,
                JsonParser.parseString("{\"model\":\"rvp:entity/weapon_mount/weapon_mount_pl12\"}")
                        .getAsJsonObject());
        RVP_PylonDisplayCache.replace(Map.of(aliasId, display));

        assertEquals(display, RVP_PylonDisplayCache.find(aliasId));
        assertEquals(display, RVP_PylonDisplayCache.find(ID));
        assertNull(RVP_PylonDisplayCache.find(new ResourceLocation("rvp", "entity/weapon_mount/unknown")));
    }
}
