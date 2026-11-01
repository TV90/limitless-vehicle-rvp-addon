package org.ywzj.rvp.weapon.visual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.util.Locale;

/** 编解码 {@code rvp:mchr_explosion} 事件专用的轻量运行时参数。 */
public final class RVP_DefaultExplosionEventData {
    /** 当前事件参数中承载 RVP 武器行为类型的键。 */
    private static final String WEAPON_KIND = "weapon_kind";

    /** 事件参数中承载自定义爆炸音效事件 ID 的键（可缺失，缺失按武器类别默认音色）。 */
    private static final String EXPLOSION_SOUND = "explosion_sound";

    /** 事件参数中承载是否使用大口径炮弹音效档案的键（可缺失，缺失按机枪音效处理）。 */
    private static final String CANNON_SOUND = "cannon_sound";

    /** 事件参数中承载非武器专用声音档案的内部标记键。 */
    private static final String SOUND_PROFILE = "sound_profile";

    /** 事件参数中承载“爆心离地高度（格）”的键；客户端用它和本地门槛比较。 */
    private static final String CLEARANCE = "clearance";

    /** 事件参数中承载“是否空中起爆”的旧键；仅在缺少离地高度时作为回退结论。 */
    private static final String AIR_BURST = "air_burst";

    /** 完整载具击毁声音档案的当前标记值。 */
    private static final String VEHICLE_SOUND_PROFILE = "vehicle";

    private RVP_DefaultExplosionEventData() {
    }

    /**
     * 把服务端弹体类型编码为规范化 JSON；空类型安全回退为 {@link RVP_EnumWeaponKind#ROCKET}。
     */
    public static String encode(RVP_EnumWeaponKind weaponKind) {
        return encode(weaponKind, null, false);
    }

    /**
     * 把服务端弹体类型编码为规范化 JSON；空类型安全回退为 {@link RVP_EnumWeaponKind#ROCKET}。
     *
     * @param explosionSound 自定义爆炸音效事件 ID（可空，空则不写入该键）
     */
    public static String encode(RVP_EnumWeaponKind weaponKind, @Nullable String explosionSound) {
        return encode(weaponKind, explosionSound, false);
    }

    /**
     * 把服务端弹体类型、自定义音效和大口径炮弹音效标记编码为规范化 JSON。
     *
     * @param explosionSound 自定义爆炸音效事件 ID（可空，空则不写入该键）
     * @param cannonSound 是否让 MACHINEGUN 类型使用大口径炮弹音效档案
     */
    public static String encode(RVP_EnumWeaponKind weaponKind, @Nullable String explosionSound,
                                boolean cannonSound) {
        // 旧调用点默认按“空中起爆”处理，保持加入 air_burst 之前的光源行为。
        return encode(weaponKind, explosionSound, cannonSound, true);
    }

    /**
     * 把服务端弹体类型、自定义音效、炮弹音效标记与爆心离地高度编码为规范化 JSON。
     *
     * @param explosionSound 自定义爆炸音效事件 ID（可空，空则不写入该键）
     * @param cannonSound 是否让 MACHINEGUN 类型使用大口径炮弹音效档案
     * @param clearance 爆心离地高度（格）；非有限值不写入该键，客户端按旧标记回退
     */
    public static String encode(RVP_EnumWeaponKind weaponKind, @Nullable String explosionSound,
                                boolean cannonSound, float clearance) {
        RVP_EnumWeaponKind safeKind = weaponKind == null ? RVP_EnumWeaponKind.ROCKET : weaponKind;
        JsonObject data = new JsonObject();
        data.addProperty(WEAPON_KIND, safeKind.name().toLowerCase(Locale.ROOT));
        if (explosionSound != null && !explosionSound.isBlank()) {
            data.addProperty(EXPLOSION_SOUND, explosionSound.trim());
        }
        if (cannonSound) {
            data.addProperty(CANNON_SOUND, true);
        }
        // 调用离地高度事实：只写数值，不写结论；门槛由客户端按自己的 airBurstClearance 判定。
        if (Float.isFinite(clearance)) {
            data.addProperty(CLEARANCE, clearance);
        }
        // 调用 RVP 视觉参数规范化器，保证事件载荷排序稳定并受统一字节上限约束。
        return RVP_VisualPresetDataCodec.canonicalize(data).orElse("{}");
    }

    /**
     * 把旧版的“是否空中起爆”布尔写入载荷；仅为兼容旧调用点保留。
     *
     * @param explosionSound 自定义爆炸音效事件 ID（可空，空则不写入该键）
     * @param cannonSound 是否让 MACHINEGUN 类型使用大口径炮弹音效档案
     * @param airBurst 是否在空中起爆；缺少离地高度时客户端据此回退
     */
    public static String encode(RVP_EnumWeaponKind weaponKind, @Nullable String explosionSound,
                                boolean cannonSound, boolean airBurst) {
        RVP_EnumWeaponKind safeKind = weaponKind == null ? RVP_EnumWeaponKind.ROCKET : weaponKind;
        JsonObject data = new JsonObject();
        data.addProperty(WEAPON_KIND, safeKind.name().toLowerCase(Locale.ROOT));
        if (explosionSound != null && !explosionSound.isBlank()) {
            data.addProperty(EXPLOSION_SOUND, explosionSound.trim());
        }
        if (cannonSound) {
            data.addProperty(CANNON_SOUND, true);
        }
        // 调用旧起爆性质标记：保留给未升级的服务端或其它视觉工厂，客户端仅在无离地高度时读取。
        data.addProperty(AIR_BURST, airBurst);
        // 调用 RVP 视觉参数规范化器，保证事件载荷排序稳定并受统一字节上限约束。
        return RVP_VisualPresetDataCodec.canonicalize(data).orElse("{}");
    }

    /**
     * 编码完整载具击毁专用声音档案。
     *
     * <p>载具击毁复用威尔逊冲击波视觉事件，但不属于任一 RVP 武器类型，
     * 因此只在现有 {@code preset_data} 通道中写入当前声音档案标记。</p>
     */
    public static String encodeVehicleSoundProfile() {
        JsonObject data = new JsonObject();
        data.addProperty(SOUND_PROFILE, VEHICLE_SOUND_PROFILE);
        // 调用 RVP 视觉参数规范化器，保证新增的内部标记遵守公共载荷格式限制。
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

    /**
     * 从客户端收到的事件参数解析自定义爆炸音效事件 ID；缺失、畸形或空白统一回退
     * {@code null}（按武器类别默认音色）。
     */
    @Nullable
    public static String decodeExplosionSound(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(canonicalJson);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement value = root.getAsJsonObject().get(EXPLOSION_SOUND);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return null;
            }
            String id = value.getAsString().trim();
            return id.isEmpty() ? null : id;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 判断客户端收到的事件是否在空中起爆；缺失、畸形或非布尔值按 true 处理，
     * 使加入该标记之前的事件保持原有“生成光源”的行为。
     */
    public static boolean decodeAirBurst(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return true;
        }
        try {
            JsonElement root = JsonParser.parseString(canonicalJson);
            if (!root.isJsonObject()) {
                return true;
            }
            JsonElement value = root.getAsJsonObject().get(AIR_BURST);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                return true;
            }
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /**
     * 从载荷解析爆心离地高度（格）；缺失、畸形或非有限值返回 {@code null}，
     * 由调用方回退到旧版 {@code air_burst} 标记。
     */
   @Nullable
    public static Float decodeClearance(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(canonicalJson);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement value = root.getAsJsonObject().get(CLEARANCE);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            float clearance = value.getAsFloat();
            return Float.isFinite(clearance) ? clearance : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 唯一的“是否创建屏幕光源”判定：要求事件允许闪光，且爆心离地高度达到客户端门槛。
     *
     * <p>门槛来自客户端配置（{@code airBurstClearance}，默认100格）：0 或负数表示不限制，
     * 任何爆炸（含贴地、洞穴内）都生成光源。载荷缺少离地高度时回退旧版 {@code air_burst}
     * 布尔，缺失该布尔再回退为“空中起爆”，保持加入离地高度之前的行为。</p>
     *
     * @param flash 事件是否允许闪光
     * @param canonicalJson 服务端事件载荷
     * @param clearanceThreshold 离地高度门槛，单位格；≤0 表示不限制
     */
    public static boolean shouldEmitScreenLight(boolean flash, String canonicalJson, float clearanceThreshold) {
        if (!flash) {
            return false;
        }
        // 调用新事实字段：只有同时存在离地高度时才做门槛比较。
        Float clearance = decodeClearance(canonicalJson);
        if (clearance != null) {
            return clearanceThreshold <= 0F || clearance >= clearanceThreshold;
        }
        // 调用旧标记回退：加入离地高度之前的载荷只带布尔结论。
        return decodeAirBurst(canonicalJson);
    }

    /**
     * 从客户端收到的事件参数解析大口径炮弹音效标记；缺失、畸形或非布尔值均按 false 处理。
     */
    public static boolean decodeCannonSound(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return false;
        }
        try {
            JsonElement root = JsonParser.parseString(canonicalJson);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonElement value = root.getAsJsonObject().get(CANNON_SOUND);
            return value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isBoolean()
                    && value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 判断客户端收到的事件是否使用完整载具击毁声音档案；缺失、畸形或其他档案均为 false。
     */
    public static boolean decodeVehicleSoundProfile(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return false;
        }
        try {
            JsonElement root = JsonParser.parseString(canonicalJson);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonElement value = root.getAsJsonObject().get(SOUND_PROFILE);
            return value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString()
                    && VEHICLE_SOUND_PROFILE.equals(value.getAsString().trim());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
