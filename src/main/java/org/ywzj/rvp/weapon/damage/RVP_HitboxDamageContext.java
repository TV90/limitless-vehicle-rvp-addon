package org.ywzj.rvp.weapon.damage;

public final class RVP_HitboxDamageContext {

    private static final ThreadLocal<Integer> SKIP_HITBOX = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Float> VEHICLE_HIT_DISPLAY_DAMAGE = new ThreadLocal<>();

    private RVP_HitboxDamageContext() {}

    public static boolean shouldSkipHitboxScaling() {
        return SKIP_HITBOX.get() > 0;
    }

    public static boolean shouldSkipGlobalVehicleHurtScaling() {
        return shouldSkipHitboxScaling();
    }

    public static void pushSkipGlobalVehicleHurtScaling() {
        pushSkipHitboxScaling();
    }

    public static void popSkipGlobalVehicleHurtScaling() {
        popSkipHitboxScaling();
    }

    public static void pushSkipHitboxScaling() {
        SKIP_HITBOX.set(SKIP_HITBOX.get() + 1);
    }

    public static void popSkipHitboxScaling() {
        int next = SKIP_HITBOX.get() - 1;
        if (next <= 0) {
            SKIP_HITBOX.remove();
        } else {
            SKIP_HITBOX.set(next);
        }
    }

    public static void setVehicleHitDisplayDamage(float damage) {
        if (!Float.isFinite(damage)) {
            clearVehicleHitDisplayDamage();
            return;
        }
        VEHICLE_HIT_DISPLAY_DAMAGE.set(damage);
    }

    public static float getVehicleHitDisplayDamage() {
        Float damage = VEHICLE_HIT_DISPLAY_DAMAGE.get();
        return damage == null ? Float.NaN : damage;
    }

    public static void clearVehicleHitDisplayDamage() {
        VEHICLE_HIT_DISPLAY_DAMAGE.remove();
    }
}
