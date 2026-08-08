package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** Executes exactly one guidance type from the new MAIN/TERMINAL schema. */
public final class RVP_GuidanceRuntimeController {

    private RVP_GuidanceRuntimeController() {}

    public static boolean tick(RVP_BaseBullet projectile) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null) {
            return false;
        }
        RVP_GuidanceData guidance = data.getGuidanceData();
        projectile.getGuidancePhaseState().update(
                guidance.getTerminalGuidance(),
                RVP_GuidanceTransitionContext.from(projectile)
        );
        RVP_GuidanceActiveConfig active = RVP_GuidanceModelResolver.resolveActive(
                guidance,
                projectile.getGuidancePhaseState().phase()
        );
        projectile.setActiveStageName(active.phase().name());
        projectile.setActiveSourceType(active.guidanceType());

        RVP_Range<Integer> tickRange = active.tickRange();
        if (tickRange != null && !tickRange.contains(projectile.getFlightTickCount())) {
            return false;
        }

        RVP_RuntimeGuidanceSource source = RVP_RuntimeGuidanceSourceRegistry.get(active.guidanceType());
        if (source == null) {
            return false;
        }

        RVP_GuidanceRuntimeContext context = new RVP_GuidanceRuntimeContext(projectile, data, active);
        RVP_GuidanceIntent intent = source.evaluate(context);
        if (!intent.success() && active.enableInertialGuidance()) {
            Vec3 memory = projectile.getLastGuidancePos();
            if (memory != null) {
                intent = RVP_GuidanceIntent.point(memory, false, 1.0, active.guidanceType());
            }
        }
        if (!intent.success()) {
            return false;
        }
        return RVP_GuidanceRuntimeMath.applyIntent(context, intent);
    }
}
