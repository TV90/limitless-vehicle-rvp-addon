package org.ywzj.rvp.firesupport.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportProblemCollector;

/** 炮火支援当前 schema 共用的严格 JSON 读取工具。 */
public final class RVP_FireSupportJson {
    private RVP_FireSupportJson() {}

    public static JsonObject object(JsonObject parent, String key, String path,
                             RVP_FireSupportProblemCollector problems, boolean required) {
        JsonElement value = parent.get(key);
        if (value == null && !required) return new JsonObject();
        if (value == null || !value.isJsonObject()) {
            problems.add(path + "." + key, "必须是对象");
            return new JsonObject();
        }
        return value.getAsJsonObject();
    }

    public static JsonArray array(JsonObject parent, String key, String path,
                           RVP_FireSupportProblemCollector problems) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonArray()) {
            problems.add(path + "." + key, "必须是数组");
            return new JsonArray();
        }
        return value.getAsJsonArray();
    }

    public static String string(JsonObject parent, String key, String path,
                         RVP_FireSupportProblemCollector problems) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            problems.add(path + "." + key, "必须是字符串");
            return "";
        }
        String result = value.getAsString();
        if (result.isBlank() || result.length() > 256) problems.add(path + "." + key, "不能为空且最长 256 字符");
        return result;
    }

    public static int integer(JsonObject parent, String key, int defaultValue, String path,
                       RVP_FireSupportProblemCollector problems) {
        JsonElement value = parent.get(key);
        if (value == null) return defaultValue;
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (RuntimeException ex) {
            problems.add(path + "." + key, "必须是整数");
            return defaultValue;
        }
    }

    public static double number(JsonObject parent, String key, double defaultValue, String path,
                         RVP_FireSupportProblemCollector problems) {
        JsonElement value = parent.get(key);
        if (value == null) return defaultValue;
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
            double result = value.getAsDouble();
            if (!Double.isFinite(result)) throw new NumberFormatException();
            return result;
        } catch (RuntimeException ex) {
            problems.add(path + "." + key, "必须是有限数值");
            return defaultValue;
        }
    }

    public static boolean bool(JsonObject parent, String key, boolean defaultValue, String path,
                        RVP_FireSupportProblemCollector problems) {
        JsonElement value = parent.get(key);
        if (value == null) return defaultValue;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            problems.add(path + "." + key, "必须是布尔值");
            return defaultValue;
        }
        return value.getAsBoolean();
    }

    public static ResourceLocation resource(String value, String path, RVP_FireSupportProblemCollector problems) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            problems.add(path, "不是合法资源 ID");
            return ResourceLocation.fromNamespaceAndPath("minecraft", "invalid");
        }
        return id;
    }

    public static void keys(JsonObject object, Set<String> allowed, String path,
                     RVP_FireSupportProblemCollector problems) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) problems.add(path + "." + key, "未知字段");
        }
    }

    public static boolean simpleId(String id) { return id.matches("[a-z0-9_.-]+") && id.length() <= 64; }
}
