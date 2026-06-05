package org.ywzj.rvp.weapon.damage;

/**
 * Inputs for {@link org.ywzj.rvp.weapon.util.RVP_DamageDecayUtil}; each decay rule reads one domain from this context.
 */
public record RVP_DecayContext(float distanceTraveled, float incidenceAngleDeg) {

    public static RVP_DecayContext distanceOnly(float distanceTraveled) {
        return new RVP_DecayContext(distanceTraveled, Float.NaN);
    }

    public static RVP_DecayContext withIncidence(float distanceTraveled, float incidenceAngleDeg) {
        return new RVP_DecayContext(distanceTraveled, incidenceAngleDeg);
    }

    public boolean hasIncidenceAngle() {
        return !Float.isNaN(incidenceAngleDeg);
    }
}
