package org.ywzj.rvp.guidance;

/**
 * Optional extension point for code-backed stage conditions.
 *
 * <p>JSON stages are handled by {@link RVP_GuidanceController}; this interface is
 * reserved for complex conditions that should stay outside entity subclasses.</p>
 */
public interface RVP_GuidanceStage {

    boolean isActive(RVP_GuidanceContext context);

    boolean apply(RVP_GuidanceContext context);
}
