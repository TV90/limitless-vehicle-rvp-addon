package org.ywzj.rvp.weapon.damage;

public final class RVP_HitboxDamageContext {

    private static final ThreadLocal<Integer> SKIP_GLOBAL = ThreadLocal.withInitial(() -> 0);

    private RVP_HitboxDamageContext() {}

    public static boolean shouldSkipGlobalVehicleHurtScaling() {
        return SKIP_GLOBAL.get() > 0;
    }

    public static void pushSkipGlobalVehicleHurtScaling() {
        SKIP_GLOBAL.set(SKIP_GLOBAL.get() + 1);
    }

    public static void popSkipGlobalVehicleHurtScaling() {
        int next = SKIP_GLOBAL.get() - 1;
        if (next <= 0) {
            SKIP_GLOBAL.remove();
        } else {
            SKIP_GLOBAL.set(next);
        }
    }
}

