package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.vehicle.pojo.Explosion;

/**
 * RVP 武器 {@code detonate_data.explosion_data}，继承载具包 {@link Explosion} 字段。
 *
 * <p>运行时引爆使用本体 {@link org.ywzj.vehicle.util.VehicleExplosion}。</p>
 */
public class RVP_Explosion extends Explosion {

    /**
     * 方块破坏半径（对生物杀伤半径 {@code radius} 之外独立可配，语义对齐 MCHeli 的 ExplosionBlock）：
     * <ul>
     *   <li>{@code null}（缺省）——继承 {@code radius}，破坏范围 = 杀伤范围（历史行为）；
     *   <li>{@code 0}——不破坏方块（{@code destroy_block=true} 时只伤人不破坏地形）；
     *   <li>{@code >0}——地形破坏使用独立半径，{@code radius} 只管对实体杀伤与视觉档位。
     *       大于 32 时引擎自动走核爆炸（分 tick 批量破坏 + 蘑菇云视觉）。
     * </ul>
     * 仅在 {@code destroy_block=true} 时生效；不等、有效时引爆走"地形爆炸 + 杀伤爆炸"双爆炸路径。
     */
    @SerializedName("destroy_radius")
    private Float destroyRadius;

    /** {@code null} 表示未配置（继承 {@code radius}）。 */
    public Float getDestroyRadius() {
        return destroyRadius;
    }

    /**
     * 弹药爆炸对载具的伤害倍率（2026-09-20 拆分新增，武器侧参数）：
     * 作用于本体核心距离衰减之后的爆炸伤害（面板 300 → 命中偏差衰减 270 → ×2 = 540）。
     * 仅对<b>载具目标</b>生效（经 {@code RVP_VehicleHurtScalingHandler} 爆炸分支乘算，
     * 生物目标不乘）；与受击载具侧的 {@code vehicle_explosion_damage_factor}（per-bone）
     * 独立相乘。未写 = 1.0（不缩放）。
     */
    @SerializedName("explosion_damage_factor")
    private Float explosionDamageFactor;

    /** {@code null} 表示未配置（1.0，不缩放）。 */
    public Float getExplosionDamageFactor() {
        return explosionDamageFactor;
    }

    /**
     * 爆炸药水效果列表（2026-09-22 新增）：爆炸结算后对杀伤半径内 LivingEntity（含载具乘员）
     * 施加，时长按<b>离爆心距离线性衰减</b>——爆心 = {@code duration_ticks} 满时长，
     * 杀伤半径边缘 = 0（不足 20t 视为 0 不施加）。与直击药水
     * （{@code collision_data.hit_potion_effects}，满时长）独立；与
     * {@code detonate_data.potion_effect_data}（落点范围均匀时长）互补，互不替代。
     * 未配置 = 无爆炸药水（历史行为）。
     */
    @SerializedName("potion_effects")
    private java.util.List<RVP_PotionEffectEntry> potionEffects = new java.util.ArrayList<>();

    /** 爆炸药水效果（只读快照，未配置返回空列表）。 */
    public java.util.List<RVP_PotionEffectEntry> getPotionEffects() {
        if (potionEffects == null || potionEffects.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return potionEffects;
    }

    /**
     * 自定义爆炸音效（2026-09-22）：音效事件 ID（如 {@code rvp:114514}，需在 sounds.json 注册）。
     * 配置后随默认爆炸视觉事件（preset_data 通道）下发客户端，替换按武器类别选择的
     * {@code mchr_explosion_*_near/far} 爆音（传播距离与音高抖动仍按原类别档案）。
     * 未配置 = 使用类别默认音色。
     */
    @SerializedName("explosion_sound")
    private String explosionSound;

    /** 自定义爆炸音效事件 ID（{@code null}/空白表示未配置）。 */
    public String getExplosionSound() {
        return explosionSound == null || explosionSound.isBlank() ? null : explosionSound.trim();
    }

    public static RVP_Explosion disabled() {
        RVP_Explosion explosion = new RVP_Explosion();
        explosion.explode = false;
        return explosion;
    }
}

