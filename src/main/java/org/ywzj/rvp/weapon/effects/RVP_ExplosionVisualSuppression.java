package org.ywzj.rvp.weapon.effects;

public final class RVP_ExplosionVisualSuppression {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private RVP_ExplosionVisualSuppression() {}

    public static boolean active() {
        return DEPTH.get() > 0;
    }

    public static void run(Runnable action) {
        DEPTH.set(DEPTH.get() + 1);
        try {
            action.run();
        } finally {
            int depth = DEPTH.get() - 1;
            if (depth <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth);
            }
        }
    }
}
