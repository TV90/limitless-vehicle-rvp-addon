package org.ywzj.rvp.weapon.visual;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** 规范化并限制 {@code preset_data}，保证事件不持有可变 JSON。 */
public final class RVP_VisualPresetDataCodec {
    /** 仅用于输出紧凑 JSON 的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    private RVP_VisualPresetDataCodec() {
    }

    /**
     * 深拷贝并按对象键排序；UTF-8 超过 8 KiB 时拒绝该覆盖，避免放大网络载荷。
     */
    public static Optional<String> canonicalize(JsonObject source) {
        JsonObject safeSource = source == null ? new JsonObject() : source.deepCopy();
        String canonical = GSON.toJson(sort(safeSource));
        if (canonical.getBytes(StandardCharsets.UTF_8).length > RVP_VisualEffectEvent.MAX_PRESET_DATA_BYTES) {
            return Optional.empty();
        }
        return Optional.of(canonical);
    }

    private static JsonElement sort(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray sortedArray = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                sortedArray.add(sort(child));
            }
            return sortedArray;
        }
        JsonObject source = element.getAsJsonObject();
        JsonObject sortedObject = new JsonObject();
        List<String> keys = new ArrayList<>(source.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            sortedObject.add(key, sort(source.get(key)));
        }
        return sortedObject;
    }
}
