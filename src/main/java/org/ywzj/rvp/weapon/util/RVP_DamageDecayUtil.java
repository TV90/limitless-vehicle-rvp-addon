package org.ywzj.rvp.weapon.util;

import net.minecraft.util.Mth;
import org.ywzj.rvp.weapon.damage.RVP_DecayContext;
import org.ywzj.rvp.weapon.data.RVP_DamageDecayRuleData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code collision_data.damage_decay}：{@code domain=distance} 规则相乘，
 * {@code domain=angle} 规则分段互斥，两类结果再相乘。
 */
public final class RVP_DamageDecayUtil {

    private RVP_DamageDecayUtil() {}

    public static float combinedFactor(List<RVP_DamageDecayRuleData> rules, float distanceTraveled) {
        return combinedFactor(rules, RVP_DecayContext.distanceOnly(distanceTraveled));
    }

    public static float combinedFactor(List<RVP_DamageDecayRuleData> rules, RVP_DecayContext context) {
        if (rules == null || rules.isEmpty() || context == null) {
            return 1f;
        }
        List<RVP_DamageDecayRuleData> distanceRules = new ArrayList<>();
        List<RVP_DamageDecayRuleData> angleRules = new ArrayList<>();
        for (RVP_DamageDecayRuleData rule : rules) {
            if (rule.isAngleDomain()) {
                angleRules.add(rule);
            } else {
                distanceRules.add(rule);
            }
        }
        float factor = 1f;
        for (RVP_DamageDecayRuleData rule : distanceRules) {
            factor *= rule.evaluateSample(Math.max(context.distanceTraveled(), 0f));
        }
        factor *= angleFactor(angleRules, context.incidenceAngleDeg());
        return Mth.clamp(factor, 0f, 1f);
    }

    public static float distanceFactor(List<RVP_DamageDecayRuleData> rules, float distanceTraveled) {
        if (rules == null || rules.isEmpty()) {
            return 1f;
        }
        float factor = 1f;
        for (RVP_DamageDecayRuleData rule : rules) {
            if (!rule.isAngleDomain()) {
                factor *= rule.evaluateSample(Math.max(distanceTraveled, 0f));
            }
        }
        return Mth.clamp(factor, 0f, 1f);
    }

    public static float angleFactor(List<RVP_DamageDecayRuleData> rules, float angleDeg) {
        if (rules == null || rules.isEmpty() || Float.isNaN(angleDeg)) {
            return 1f;
        }
        List<RVP_DamageDecayRuleData> angleRules = new ArrayList<>();
        for (RVP_DamageDecayRuleData rule : rules) {
            if (rule.isAngleDomain()) {
                angleRules.add(rule);
            }
        }
        if (angleRules.isEmpty()) {
            return 1f;
        }
        angleRules.sort(Comparator.comparing(RVP_DamageDecayRuleData::getStartDistance));
        if (angleDeg < angleRules.get(0).getStartDistance()) {
            return 1f;
        }
        for (RVP_DamageDecayRuleData rule : angleRules) {
            float start = rule.getStartDistance();
            float end = rule.getBandEnd();
            if (angleDeg < start || angleDeg > end) {
                continue;
            }
            return rule.evaluateSample(angleDeg);
        }
        return 1f;
    }
}
