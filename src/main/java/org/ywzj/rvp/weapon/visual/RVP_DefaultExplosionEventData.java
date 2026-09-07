package org.ywzj.rvp.weapon.visual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.util.Locale;

/** 编解码 {@code rvp:mchr_explosion} 事件专用的轻量运行时参数。 */
public final class RVP_DefaultExplosionEventData {
    /** 当前事件参数中承载 RVP 武器行为类型的键。 */
    private static final String WEAPON_KIND = "weapon_kind";

    private RVP_DefaultExplosionEventData() {
    }

    /**
     * 把服务端弹体类型编码为规范化 JSON；空类型安全回退为 {@link RVP_EnumWeaponKind#ROCKET}。
     */
    public static String encode(RVP_EnumWeaponKind weaponKind) {
        RVP_EnumWeaponKind safeKind = weaponKind == null ? RVP_EnumWeaponKind.ROCKET : weaponKind;
        JsonObject data = new JsonObject();
        data.addProperty(WEAPON_KIND, safeKind.name().toLowerCase(Locale.ROOT));
        // 调用 RVP 视觉参数规范化器，保证事件载荷排序稳定并受统一字节上限约束。
        return RVP_VisualPresetDataCodec.canonicalize(data).orElse("{}");
    }

    /**
     * 从客户端收到的事件参数解析武器类型；畸形、缺失或未知值统一回退为火箭音色。
     */
    public static RVP_EnumWeaponKind decodeWeaponKind(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return RVP_EnumWeaponKind.ROCKET;
        }
        try {
            JsonElement root = JsonParser.parseString(canonicalJson);
            if (!root.isJsonObject()) {
                return RVP_EnumWeaponKind.ROCKET;
            }
            JsonElement value = root.getAsJsonObject().get(WEAPON_KIND);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return RVP_EnumWeaponKind.ROCKET;
            }
            return RVP_EnumWeaponKind.valueOf(value.getAsString().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return RVP_EnumWeaponKind.ROCKET;
        }
    }
}
