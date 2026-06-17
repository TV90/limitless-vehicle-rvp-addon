package org.ywzj.rvp.ext;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public interface MissileEntityArmExt {

    void ywzj_rvp$initArmState();

    int ywzj_rvp$getArmMemoryLeftTick();

    void ywzj_rvp$setArmMemoryLeftTick(int ticks);

    @Nullable
    Vec3 ywzj_rvp$getArmLastSeenPos();

    void ywzj_rvp$setArmLastSeenPos(@Nullable Vec3 pos);

    int ywzj_rvp$getArmTargetVehicleId();

    void ywzj_rvp$setArmTargetVehicleId(int id);

    /** The preselected target vehicle ID copied from the weapon unit at launch. */
    int ywzj_rvp$getArmPreselectVehicleId();

    void ywzj_rvp$setArmPreselectVehicleId(int id);

    /** The preselected target radar index. */
    int ywzj_rvp$getArmPreselectRadarIndex();

    void ywzj_rvp$setArmPreselectRadarIndex(int index);
}
