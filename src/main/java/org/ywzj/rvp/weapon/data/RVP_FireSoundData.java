package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 包内显式发射声音配置；不按武器 ID、载具类型、口径或冷发射状态自动选择音频。 */
public class RVP_FireSoundData {
    /** 必填，声音事件 ID（由包 assets/<namespace>/sounds.json 注册）；缺省不接管原播放。 */
    @SerializedName("sound") private ResourceLocation sound;
    /** 可选，远距离事件 ID；缺省始终使用 sound。 */
    @SerializedName("far_sound") private ResourceLocation farSound;
    /** 近远分界，单位格；只有 far_sound 存在且本值在 (0,max_distance) 内才启用。 */
    @SerializedName("far_distance") private float farDistance;
    /** 必填，最远播放/兜底广播距离，单位格，缺省0不接管；样本衰减另由 sounds.json 控制。 */
    @SerializedName("max_distance") private float maxDistance;
    /** 播放音量倍率，缺省1，须为有限正数；与原版样本衰减共同决定最终听感。 */
    @SerializedName("volume") private float volume = 1F;
    /** 音调/播放速率倍率，缺省1，须为有限正数。 */
    @SerializedName("pitch") private float pitch = 1F;

    public RVP_FireSoundData() {}
    /** 构造网络快照及测试配置，不推导任何类型相关默认值。 */
    public RVP_FireSoundData(ResourceLocation sound, @Nullable ResourceLocation farSound,
                             float farDistance, float maxDistance, float volume, float pitch) {
        this.sound = sound; this.farSound = farSound; this.farDistance = farDistance;
        this.maxDistance = maxDistance; this.volume = volume; this.pitch = pitch;
    }
    public boolean isConfigured() {
        return sound != null && positive(maxDistance) && positive(volume) && positive(pitch)
                && (farSound == null || positive(farDistance) && farDistance < maxDistance);
    }
    private static boolean positive(float value) { return Float.isFinite(value) && value > 0; }
    public ResourceLocation getSound() { return sound; }
    @Nullable public ResourceLocation getFarSound() { return farSound; }
    public float getFarDistance() { return farDistance; }
    public float getMaxDistance() { return maxDistance; }
    public float getVolume() { return volume; }
    public float getPitch() { return pitch; }
    /** 按包内距离阈值选事件；分界点属于近音，不把固定512或特定名称写在播放代码中。 */
    public ResourceLocation soundAt(double distance) {
        return farSound != null && distance > farDistance ? farSound : sound;
    }
}
