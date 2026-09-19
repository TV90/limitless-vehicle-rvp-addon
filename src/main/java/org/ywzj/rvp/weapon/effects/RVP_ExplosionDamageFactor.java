package org.ywzj.rvp.weapon.effects;

/**
 * "弹药爆炸伤害倍率"窗口（2026-09-20 拆分，武器侧参数
 * {@code detonate_data.explosion_data.explosion_damage_factor}）：
 * 窗口激活期间，{@code RVP_VehicleHurtScalingHandler} 的爆炸分支会把窗口值乘进
 * 本体距离衰减后的爆炸伤害（仅载具目标；生物不乘，语义为"爆炸对载具伤害倍率"）。
 *
 * <p>由 {@code RVP_BaseBullet.triggerExplosion} 包住爆炸调用（destroy_radius 拆分的
 * 爆炸 A/B 都包）；与 {@link RVP_ExplosionImmediatePath}、
 * {@link RVP_TerrainOnlyExplosion} 窗口彼此独立、可嵌套。窗口外 current() 恒 1
 * （本体/其它 mod 的爆炸不受影响）。</p>
 */
public final class RVP_ExplosionDamageFactor {

    private static final ThreadLocal<Float> FACTOR = ThreadLocal.withInitial(() -> 1.0F);

    private RVP_ExplosionDamageFactor() {}

    /** 当前窗口倍率（窗口外恒 1）。 */
    public static float current() {
        return FACTOR.get();
    }

    public static void run(float factor, Runnable action) {
        FACTOR.set(factor > 0f ? factor : 1f);
        try {
            action.run();
        } finally {
            FACTOR.remove();
        }
    }
}
