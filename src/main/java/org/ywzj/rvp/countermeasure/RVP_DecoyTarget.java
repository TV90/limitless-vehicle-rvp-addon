package org.ywzj.rvp.countermeasure;

import net.minecraft.world.phys.Vec3;

/**
 * Synthetic decoy target descriptor for future countermeasure simulation.
 *
 * <p>Current in-game decoys use {@link org.ywzj.vehicle.api.entity.TargetObstruction}
 * entities; {@link RVP_CountermeasureState#findDecoyTarget} redirects seekers to those.
 * This record is kept for framework APIs that may materialize decoys without a full entity.</p>
 */
public record RVP_DecoyTarget(Vec3 position, int expireTick, float signatureStrength) {
}
