package org.ywzj.rvp.util;

import org.ywzj.rvp.weapon.data.RVP_DamageFactor;

/**
 * {@link org.ywzj.rvp.util.RVP_Explosion} 爆炸伤害期间向 {@link org.ywzj.rvp.mixin.VehicleExplosionMixin} 传递倍率。
 */
public final class RVP_ExplosionDamageContext {

    private static final ThreadLocal<RVP_DamageFactor> ACTIVE = new ThreadLocal<>();

    private RVP_ExplosionDamageContext() {
    }

    public static void run(RVP_DamageFactor factor, Runnable action) {
        ACTIVE.set(factor == null ? RVP_DamageFactor.DEFAULT : factor);
        try {
            action.run();
        } finally {
            ACTIVE.remove();
        }
    }

    public static RVP_DamageFactor current() {
        RVP_DamageFactor factor = ACTIVE.get();
        return factor == null ? RVP_DamageFactor.DEFAULT : factor;
    }
}
