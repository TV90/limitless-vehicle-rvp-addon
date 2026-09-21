package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 单条药水效果配置（2026-09-22 MCHeli {@code AddPotionEffect} 机制移植）：
 * 被 {@code collision_data.hit_potion_effects}（直击命中）与
 * {@code explosion_data.potion_effects}（爆炸，按爆心距离衰减时长）共用。
 * 同一列表可写多条以叠加多种效果，逐条独立施加。
 */
public class RVP_PotionEffectEntry {

    /**
     * 药水注册 ID，如 {@code minecraft:slowness} 或 {@code slowness}（缺省命名空间自动补
     * {@code minecraft:}）。无效 ID 该条静默跳过。单位与语义对齐原版
     * {@link net.minecraft.world.effect.MobEffectInstance}。
     */
    @SerializedName("effect")
    private String effect = "";

    /**
     * 基础效果时长（tick）。直击命中按该值满时长施加；爆炸按离爆心距离线性衰减
     * （爆心 = 该值，杀伤半径边缘 = 0，见 RVP_HitPotionEffectService.scaledDuration）。
     */
    @SerializedName("duration_ticks")
    private int durationTicks = 100;

    /**
     * 效果等级（amplifier，0 计数：0 = 效果 I，1 = 效果 II）。
     */
    @SerializedName("amplifier")
    private int amplifier = 0;

    /**
     * 目标过滤：{@code all}（默认，全部 LivingEntity）、{@code players}（仅玩家）、
     * {@code hostile}（仅敌对生物）、{@code non_allied}（排除发射者本人与其载具乘员）。
     */
    @SerializedName("targets")
    private String targets = "all";

    /** 是否有效配置（effect 非空才算，否则该条静默跳过）。 */
    public boolean isActive() {
        return effect != null && !effect.isBlank();
    }

    /** 药水注册 ID（trim 后）。 */
    public String getEffect() {
        return effect == null ? "" : effect.trim();
    }

    /** 基础时长（tick），下限 1。 */
    public int getDurationTicks() {
        return Math.max(durationTicks, 1);
    }

    /** 效果等级，下限 0。 */
    public int getAmplifier() {
        return Math.max(amplifier, 0);
    }

    /** 目标过滤（空白回退 {@code all}）。 */
    public String getTargets() {
        return targets == null || targets.isBlank() ? "all" : targets.trim().toLowerCase();
    }
}
