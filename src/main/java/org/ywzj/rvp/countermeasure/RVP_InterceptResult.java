package org.ywzj.rvp.countermeasure;

/**
 * Hard-kill countermeasure outcome (e.g. APS intercept).
 *
 * <p>Soft-kill / jam / decoy queries use {@link RVP_CountermeasureState.Result}.
 * This type is reserved for explicit intercept reporting when APS logic is wired in.</p>
 */
public record RVP_InterceptResult(boolean intercepted, String reason) {
    public static final RVP_InterceptResult NONE = new RVP_InterceptResult(false, "");
}
