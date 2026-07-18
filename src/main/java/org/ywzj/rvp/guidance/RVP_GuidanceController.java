package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** Stable projectile entry point for the new MAIN/TERMINAL runtime. */
public final class RVP_GuidanceController {

    private RVP_GuidanceController() {}

    public static void tick(RVP_BaseBullet projectile) {
        RVP_GuidanceRuntimeController.tick(projectile);
    }
}
