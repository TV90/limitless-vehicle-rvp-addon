package org.ywzj.rvp.weapon.damage;

import net.minecraft.util.Mth;
import org.ywzj.rvp.weapon.data.RVP_DamageDecayRuleData;

import java.util.List;

/**
 * MCH-style bullet damage decay: each rule returns a factor in {@code [0, 1]}; rules multiply together.
 *
 * @see mcheli.weapon.MCH_EntityBaseBullet#onImpact
 */
public final class RVP_DamageDecayEvaluator {

    private RVP_DamageDecayEvaluator() {}

    public static float combinedFactor(List<RVP_DamageDecayRuleData> rules, float distanceTraveled) {
        if (rules == null || rules.isEmpty()) {
            return 1f;
        }
        float factor = 1f;
        for (RVP_DamageDecayRuleData rule : rules) {
            factor *= rule.evaluate(distanceTraveled);
        }
        return Mth.clamp(factor, 0f, 1f);
    }
}
