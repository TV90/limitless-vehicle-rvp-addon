package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * Immutable input for pluggable guidance sources and JSON-backed stage hooks.
 *
 * <p>Used by {@link RVP_GuidanceSource} and {@link RVP_GuidanceStage}; the default
 * missile path still goes through {@link RVP_GuidanceController}.</p>
 */
public record RVP_GuidanceContext(RVP_BaseBullet projectile, RVP_WeaponData data) {
}
