package org.ywzj.rvp.guidance;

/**
 * Pluggable guidance source. Sources only decide target/steering and should not
 * perform explosion, damage, submunition or generic projectile effects.
 *
 * <p>Framework extension point — register implementations when a weapon needs
 * logic that does not fit {@link RVP_GuidanceController} JSON stages.</p>
 */
public interface RVP_GuidanceSource {

    RVP_EnumGuidanceType type();

    boolean apply(RVP_GuidanceContext context);
}
