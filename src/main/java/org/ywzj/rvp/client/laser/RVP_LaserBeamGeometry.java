package org.ywzj.rvp.client.laser;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_LaserVisualData;
import org.ywzj.rvp.weapon.laser.RVP_LaserRaycast;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Client entry for {@link RVP_LaserBeam} using weapon visual config.
 */
public final class RVP_LaserBeamGeometry {

    private RVP_LaserBeamGeometry() {}

    public static RVP_LaserBeam compute(Level level, AbstractVehicle vehicle, @Nullable LivingEntity shooter,
                                       Vec3 muzzle, Vec3 lookDirection, float maxRange,
                                       RVP_LaserVisualData visual) {
        double renderStart = visual == null ? 0.0 : visual.getRenderStartDistance();
        return RVP_LaserRaycast.computeBeam(level, vehicle, shooter, muzzle, lookDirection, maxRange, renderStart);
    }
}
