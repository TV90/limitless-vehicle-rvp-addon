package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

/**
 * 让 {@code explosion_damage_factor} 同时接受两种 JSON 形态（2026-10-12 按机型改造）：
 * <ul>
 *   <li>数字：{@code 2.0} ⇒ 等价于 {@code vehicle_default = 2.0}（对所有载具统一，保持旧语义）</li>
 *   <li>对象：{@code { "vehicle_default": 1.0, "vehicles": { "<实体类型ID>": 2.0 } }} ⇒ 按机型</li>
 * </ul>
 * 仅通过字段级 {@code @JsonAdapter} 生效，不改动 <b>类型级</b> 解析，
 * 因此 {@code collision_data.direct_damage_factor} 的行为完全不受影响。
 * 数字 {@code null}/{@code <=0}/非有限 ⇒ {@code null}（视为未配置，1.0 不缩放）。
 */
public final class RVP_DamageFactorAdapter
        implements JsonSerializer<RVP_DamageFactor>, JsonDeserializer<RVP_DamageFactor> {

    @Override
    public RVP_DamageFactor deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
        if (json == null || json.isJsonNull()) {
            return null;
        }
        // 数字形态 → 包装成 {"vehicle_default": v}
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            float v = json.getAsFloat();
            if (!Float.isFinite(v) || v <= 0f) {
                return null;
            }
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("vehicle_default", v);
            return context.deserialize(wrapper, RVP_DamageFactor.class);
        }
        if (!json.isJsonObject()) {
            return null;
        }
        return context.deserialize(json, RVP_DamageFactor.class);
    }

    @Override
    public JsonElement serialize(RVP_DamageFactor src, Type typeOfSrc, JsonSerializationContext context) {
        return src == null ? JsonNull.INSTANCE : context.serialize(src, RVP_DamageFactor.class);
    }
}
