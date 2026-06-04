package org.ywzj.rvp.client;

/**
 * Toggle state for on-screen RVP weapon JSON debug (MCH {@code TestMode} style).
 */
public final class RVP_WeaponTestMode {

    private static boolean enabled;

    private RVP_WeaponTestMode() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
