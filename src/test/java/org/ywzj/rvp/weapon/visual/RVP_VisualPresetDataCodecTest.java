package org.ywzj.rvp.weapon.visual;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_VisualPresetDataCodecTest {
    @Test
    void canonicalizationSortsNestedObjectKeysAndPreservesArrays() {
        JsonObject nested = new JsonObject();
        nested.addProperty("z", 2);
        nested.addProperty("a", 1);
        JsonObject root = new JsonObject();
        root.add("nested", nested);
        root.addProperty("alpha", true);

        assertEquals("{\"alpha\":true,\"nested\":{\"a\":1,\"z\":2}}",
                RVP_VisualPresetDataCodec.canonicalize(root).orElseThrow());
    }

    @Test
    void payloadOverEightKiBIsRejected() {
        JsonObject root = new JsonObject();
        root.addProperty("value", "x".repeat(RVP_VisualEffectEvent.MAX_PRESET_DATA_BYTES));

        assertTrue(RVP_VisualPresetDataCodec.canonicalize(root).isEmpty());
    }
}
