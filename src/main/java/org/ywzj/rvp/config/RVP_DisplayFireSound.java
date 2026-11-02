package org.ywzj.rvp.config;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

/** 武器 display 的 fire_sound 参数；事件名只取原生 sounds.fire / sounds.fire_far。双端安全。 */
public record RVP_DisplayFireSound(ResourceLocation sound, @Nullable ResourceLocation farSound, Settings settings) {
    /** 仅 display 持有这些表现参数，不属于武器战斗数据。未声明时完全沿用原生播放。 */
    public static final class Settings {
        /** 最远可听及远距通知范围，单位格；默认0表示不扩展原生播放。 */
        @SerializedName("max_distance") public float maxDistance;
        /** fire_far 的切换距离，单位格；默认0；配置远音轨时须在 (0,max_distance) 内。 */
        @SerializedName("far_distance") public float farDistance;
        /** 音量倍率，默认1，必须是有限正数；样本衰减由 sounds.json 声明。 */
        @SerializedName("volume") public float volume = 1F;
        /** 音调/播放速率倍率，默认1，必须是有限正数。 */
        @SerializedName("pitch") public float pitch = 1F;
    }
    @Nullable public static RVP_DisplayFireSound bind(@Nullable Settings settings, @Nullable ResourceLocation fire,
                                                     @Nullable ResourceLocation far) {
        if (settings == null || fire == null || !positive(settings.maxDistance) || !positive(settings.volume)
                || !positive(settings.pitch) || far != null && (!positive(settings.farDistance)
                || settings.farDistance >= settings.maxDistance)) return null;
        return new RVP_DisplayFireSound(fire, far, settings);
    }
    /** 服务端只读 display JSON，不加载任何客户端模型/声音类。 */
    @Nullable public static RVP_DisplayFireSound parse(JsonObject display) {
        try {
            if (!display.has("fire_sound") || !display.has("sounds")) return null;
            var sounds = display.getAsJsonObject("sounds");
            return bind(GsonUtil.GSON.fromJson(display.get("fire_sound"), Settings.class),
                    sounds.has("fire") ? ResourceLocation.tryParse(sounds.get("fire").getAsString()) : null,
                    sounds.has("fire_far") ? ResourceLocation.tryParse(sounds.get("fire_far").getAsString()) : null);
        } catch (RuntimeException invalid) { return null; }
    }
    private static boolean positive(float value) { return Float.isFinite(value) && value > 0; }
    public ResourceLocation getSound() { return sound; }
    public ResourceLocation getFarSound() { return farSound; }
    public float getFarDistance() { return settings.farDistance; }
    public float getMaxDistance() { return settings.maxDistance; }
    public float getVolume() { return settings.volume; }
    public float getPitch() { return settings.pitch; }
    public ResourceLocation soundAt(double distance) {
        return farSound != null && distance > settings.farDistance ? farSound : sound;
    }
}
