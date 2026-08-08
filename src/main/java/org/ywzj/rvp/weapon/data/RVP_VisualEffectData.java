package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Optional;

/**
 * 一次爆炸视觉事件的通用数据配置。该类只描述公共配置，不包含任何客户端渲染类型。
 */
public final class RVP_VisualEffectData {

    /** 为 {@code true} 时启用该视觉；默认 {@code false}，且仅在 {@link #effectType} 合法时生效。 */
    @SerializedName("enabled")
    private boolean enabled = false;

    /** 效果类型资源 ID；默认空，仅作为客户端工厂类型，不得用于区分武器 ID。 */
    @SerializedName("effect_type")
    private String effectType = "";

    /** 客户端预设资源 ID；默认 {@code rvp:default}，工厂无法识别时由对应工厂回退。 */
    @SerializedName("preset")
    private String preset = "rvp:default";

    /** 视觉尺寸倍率；默认 {@code 1.0}，生效时钳制到 {@code 0.1~8.0}，不改变爆炸伤害与半径。 */
    @SerializedName("scale")
    private float scale = 1.0F;

    /** 服务端允许的最大视觉密度比例；默认 {@code 1.0}，生效时钳制到 {@code 0.05~1.0}。 */
    @SerializedName("density")
    private float density = 1.0F;

    /** 持续时间（tick）；默认 {@code -1} 使用预设，正数覆盖预设并钳制到最多 {@code 600} tick。 */
    @SerializedName("duration_ticks")
    private int durationTicks = -1;

    /** 服务端广播距离（格）；默认 {@code 768}，生效时钳制到 {@code 32~2048}，不影响伤害范围。 */
    @SerializedName("broadcast_range")
    private double broadcastRange = 768.0D;

    /** 是否允许客户端播放该视觉的声音；默认 {@code true}，客户端设置仍可关闭。 */
    @SerializedName("sound")
    private boolean sound = true;

    /** 是否允许客户端显示近距离闪光；默认 {@code true}，客户端无障碍设置仍可关闭。 */
    @SerializedName("flash")
    private boolean flash = true;

    /** 是否允许客户端施加镜头震动；默认 {@code true}，客户端设置仍可关闭或缩放。 */
    @SerializedName("shake")
    private boolean shake = true;

    /**
     * 视觉事件成功发布后是否屏蔽本体普通爆炸视觉；默认 {@code true}，不影响本体伤害与方块破坏。
     */
    @SerializedName("suppress_native_explosion_effect")
    private boolean suppressNativeExplosionEffect = true;

    /**
     * 与 {@link #preset} 所指预设使用相同 schema 的稀疏覆盖对象；默认空对象，仅由对应客户端工厂类型化校验。
     */
    @SerializedName("preset_data")
    private JsonObject presetData = new JsonObject();

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<ResourceLocation> getEffectType() {
        return parseLocation(effectType);
    }

    public ResourceLocation getPreset() {
        return parseLocation(preset).orElseGet(() -> ResourceLocation.fromNamespaceAndPath("rvp", "default"));
    }

    public float getScale() {
        return Mth.clamp(Float.isFinite(scale) ? scale : 1.0F, 0.1F, 8.0F);
    }

    public float getDensity() {
        return Mth.clamp(Float.isFinite(density) ? density : 1.0F, 0.05F, 1.0F);
    }

    public int getDurationTicks() {
        return durationTicks < 0 ? -1 : Mth.clamp(durationTicks, 1, 600);
    }

    public double getBroadcastRange() {
        double finiteRange = Double.isFinite(broadcastRange) ? broadcastRange : 768.0D;
        return Mth.clamp(finiteRange, 32.0D, 2048.0D);
    }

    public boolean isSound() {
        return sound;
    }

    public boolean isFlash() {
        return flash;
    }

    public boolean isShake() {
        return shake;
    }

    public boolean isSuppressNativeExplosionEffect() {
        return suppressNativeExplosionEffect;
    }

    /** 返回独立副本，避免调用方修改武器数据模型中保存的 JSON 对象。 */
    public JsonObject copyPresetData() {
        return presetData == null ? new JsonObject() : presetData.deepCopy();
    }

    private static Optional<ResourceLocation> parseLocation(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ResourceLocation.tryParse(value.trim()));
    }
}
