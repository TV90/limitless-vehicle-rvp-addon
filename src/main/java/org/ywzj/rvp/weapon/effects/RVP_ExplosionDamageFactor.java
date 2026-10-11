package org.ywzj.rvp.weapon.effects;

import org.ywzj.rvp.weapon.data.RVP_DamageFactor;

/**
 * "弹药爆炸伤害倍率"窗口（2026-09-20 拆分，武器侧参数
 * {@code detonate_data.explosion_data.explosion_damage_factor}）：
 * 窗口激活期间，{@code RVP_VehicleHurtScalingHandler} 的爆炸分支把窗口中的
 * {@link RVP_DamageFactor 倍率表}按<b>受击载具的实体类型</b>求值后乘进本体距离衰减
 * 后的爆炸伤害（仅载具目标；生物不乘，语义为"爆炸对载具伤害倍率"）。
 *
 * <p>由 {@code RVP_BaseBullet.triggerExplosion} 包住爆炸调用（destroy_radius 拆分的
 * 爆炸 A/B 都包）；与 {@link RVP_ExplosionImmediatePath}、
 * {@link RVP_TerrainOnlyExplosion} 窗口彼此独立、可嵌套。窗口外 current() 返回
 * {@code null}（等价 1.0，本体/其它 mod 的爆炸不受影响）。</p>
 *
 * <p>2026-10-12：由 {@code ThreadLocal<Float>} 改为 {@code ThreadLocal<RVP_DamageFactor>}，
 * 以支持按机型给倍率（数字写法经 adapter 归一为 {@code vehicle_default}，语义不变）。</p>
 */
public final class RVP_ExplosionDamageFactor {

    private static final ThreadLocal<RVP_DamageFactor> FACTOR = new ThreadLocal<>();

    private RVP_ExplosionDamageFactor() {}

    /** 当前窗口倍率表；窗口外返回 {@code null}（等价 1.0，不缩放）。 */
    public static RVP_DamageFactor current() {
        return FACTOR.get();
    }

    public static void run(RVP_DamageFactor factor, Runnable action) {
        RVP_DamageFactor previous = FACTOR.get();
        FACTOR.set(factor);
        try {
            action.run();
        } finally {
            // 保存/恢复而非直接 remove：支持窗口嵌套（原地 remove 会丢掉外层窗口）
            if (previous == null) {
                FACTOR.remove();
            } else {
                FACTOR.set(previous);
            }
        }
    }
}
