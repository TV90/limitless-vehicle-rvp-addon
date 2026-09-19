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

    public static RVP_Explosion disabled() {
        RVP_Explosion explosion = new RVP_Explosion();
        explosion.explode = false;
        return explosion;
    }
}

