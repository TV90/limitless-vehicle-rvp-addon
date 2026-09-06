package org.ywzj.rvp.firesupport;

/** 炮火支援引用武器的不可配置绝对性能预算。 */
public final class RVP_FireSupportWeaponBudget {
    /** 单个顶层弹体允许的最大生命期，单位 Tick。 */ public static final int MAX_LIFE_TICKS = 72000;
    /** 单个顶层弹体允许直接展开的最大子实体数。 */ public static final int MAX_DIRECT_CHILDREN = 4096;
    /** 单任务顶层弹体与直接子实体合计上限。 */ public static final int MAX_EXPANDED_ENTITIES_PER_MISSION = Integer.MAX_VALUE;
    /** 可由炮火 profile 引用的主爆炸最大半径，单位格。 */ public static final float MAX_EXPLOSION_RADIUS = 512.0F;
    /** 可由炮火 profile 引用的主爆炸最大伤害。 */ public static final float MAX_EXPLOSION_DAMAGE = 1_000_000_000.0F;

    private RVP_FireSupportWeaponBudget() {}

    /**
     * 校验单发武器和 profile 顶层弹数共同形成的最坏实体预算。
     * 多级子母弹不再仅因链路存在而拒绝；仍对当前武器可见的直接展开量施加宽松绝对上限。
     */
    public static void validate(RVP_FireSupportResolvedWeapon weapon, int maxTopLevelRounds,
                                RVP_FireSupportProblemCollector problems, String path) {
        if (weapon.lifeTicks() < 1 || weapon.lifeTicks() > MAX_LIFE_TICKS) {
            problems.add(path, "弹体 life 必须在 [1, 72000] Tick 内");
        }
        if (weapon.directSubmunitionCount() < 0 || weapon.directSubmunitionCount() > MAX_DIRECT_CHILDREN) {
            problems.add(path, "单发直接子实体数超过 4096 的绝对预算");
        }
        if (!Float.isFinite(weapon.explosionRadius()) || weapon.explosionRadius() < 0.0F
                || weapon.explosionRadius() > MAX_EXPLOSION_RADIUS) {
            problems.add(path, "主爆炸半径超过 512 格的绝对预算或不是有限数");
        }
        if (!Float.isFinite(weapon.explosionDamage()) || weapon.explosionDamage() < 0.0F
                || weapon.explosionDamage() > MAX_EXPLOSION_DAMAGE) {
            problems.add(path, "主爆炸伤害超过 1000000000 的绝对预算或不是有限数");
        }
        long expanded = estimateExpandedEntities(maxTopLevelRounds, weapon.directSubmunitionCount());
        if (expanded > MAX_EXPANDED_ENTITIES_PER_MISSION) {
            problems.add(path, "最坏实体展开数 " + expanded + " 超过单任务绝对上限 Integer.MAX_VALUE");
        }
    }

    /** @return 顶层弹体与其直接子实体的饱和最坏总数。 */
    public static long estimateExpandedEntities(int topLevelRounds, int directChildrenPerRound) {
        if (topLevelRounds <= 0 || directChildrenPerRound < 0) return 0L;
        try {
            return Math.multiplyExact((long) topLevelRounds, Math.addExact((long) directChildrenPerRound, 1L));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
