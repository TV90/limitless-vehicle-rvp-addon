package org.ywzj.rvp.ext;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public interface WeaponUnitArmExt {

    int ywzj_rvp$getArmPreselectedVehicleId();

    int ywzj_rvp$getArmPreselectedRadarIndex();

    @Nullable
    Vec3 ywzj_rvp$getArmPreselectedPos();

    void ywzj_rvp$setArmPreselected(int vehicleId, int radarIndex, @Nullable Vec3 pos);
}
