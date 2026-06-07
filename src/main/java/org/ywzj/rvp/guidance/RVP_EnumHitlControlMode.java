package org.ywzj.rvp.guidance;

/**
 * How the player interacts while {@code human_in_the_loop} camera view is active.
 *
 * <ul>
 *   <li>{@link #VIEW} — camera follows the projectile; underlying guidance unchanged.</li>
 *   <li>{@link #MOUSE} — free-look mouse steering, typically paired with {@link RVP_EnumGuidanceType#MCLOS}.</li>
 *   <li>{@link #DESIGNATE} — BF2-style TV + SACLOS: body-locked seeker, limited mouse look, R to re-designate target.</li>
 * </ul>
 */
public enum RVP_EnumHitlControlMode {
    VIEW,
    MOUSE,
    DESIGNATE
}
