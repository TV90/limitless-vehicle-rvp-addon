package org.ywzj.rvp.weapon.damage;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.weapon.data.RVP_DamageFactor;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * 将 {@link RVP_DamageFactor} 应用于直击、近炸直伤等单次伤害。
 */
public final class RVP_DamageApplier {

    private RVP_DamageApplier() {
    }

    public static float applyScaled(float baseDamage, Entity target, RVP_DamageFactor factor) {
        if (baseDamage <= 0f || factor == null || !factor.isConfigured()) {
            return baseDamage;
        }
        return baseDamage * factor.getFactor(target);
    }

    public static float applyScaled(float baseDamage, Entity target, RVP_WeaponData data) {
        if (data == null) {
            return baseDamage;
        }
        return applyScaled(baseDamage, target, data.getDamageFactor());
    }
}
