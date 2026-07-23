package org.ywzj.rvp.ext;

import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.List;

public interface RVPPhysicsOnlyCollisionAccess {

    List<VehicleCubeOBB> rvp$getPhysicsOnlyCubes();

    void rvp$setPhysicsOnlyCubes(List<VehicleCubeOBB> cubes);
}
