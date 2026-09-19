package org.ywzj.rvp.weapon.effects;

/**
 * "纯地形爆炸"窗口标记：激活期间，本模组的 {@code VehicleExplosionHurtSkipMixin}
 * 会跳过本体 {@code VehicleExplosion.hurt(...)} 的实体伤害结算。
 *
 * <p>用于 destroy_radius 参数拆分出的"地形爆炸"（爆炸 A）：它只负责按破坏半径
 * 摧毁/烧灼方块（复用引擎的 ≤32 即时与 >32 核爆炸两条破坏路径），对生物/实体的
 * 杀伤与视觉档位由同一次引爆的"杀伤爆炸"（爆炸 B）按杀伤半径完成。若不跳过，
 * 爆炸 A 会以 0 伤害对范围内实体广播 {@code LivingHurtEvent} 并走
 * {@code EntityUtil.hurt}，污染事件总线并重置受击无敌帧。</p>
 */
public final class RVP_TerrainOnlyExplosion {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RVP_TerrainOnlyExplosion() {}

    public static boolean active() {
        return ACTIVE.get();
    }

    public static void run(Runnable action) {
        ACTIVE.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            ACTIVE.remove();
        }
    }
}
