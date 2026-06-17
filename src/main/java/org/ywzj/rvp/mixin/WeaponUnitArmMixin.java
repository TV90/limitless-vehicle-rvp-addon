package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.WeaponUnitArmExt;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitArmMixin implements WeaponUnitArmExt {

    @Unique
    private int ywzj_rvp$armPreselectedVehicleId = -1;

    @Unique
    private int ywzj_rvp$armPreselectedRadarIndex = -1;

    @Unique
    private boolean ywzj_rvp$armPreselectedHasPos;

    @Unique
    private double ywzj_rvp$armPreselectedX;

    @Unique
    private double ywzj_rvp$armPreselectedY;

    @Unique
    private double ywzj_rvp$armPreselectedZ;

    @Override
    public int ywzj_rvp$getArmPreselectedVehicleId() {
        return ywzj_rvp$armPreselectedVehicleId;
    }

    @Override
    public int ywzj_rvp$getArmPreselectedRadarIndex() {
        return ywzj_rvp$armPreselectedRadarIndex;
    }

    @Override
    public @Nullable Vec3 ywzj_rvp$getArmPreselectedPos() {
        if (!ywzj_rvp$armPreselectedHasPos) {
            return null;
        }
        return new Vec3(ywzj_rvp$armPreselectedX, ywzj_rvp$armPreselectedY, ywzj_rvp$armPreselectedZ);
    }

    @Override
    public void ywzj_rvp$setArmPreselected(int vehicleId, int radarIndex, @Nullable Vec3 pos) {
        this.ywzj_rvp$armPreselectedVehicleId = vehicleId;
        this.ywzj_rvp$armPreselectedRadarIndex = radarIndex;
        if (pos == null) {
            this.ywzj_rvp$armPreselectedHasPos = false;
        } else {
            this.ywzj_rvp$armPreselectedHasPos = true;
            this.ywzj_rvp$armPreselectedX = pos.x;
            this.ywzj_rvp$armPreselectedY = pos.y;
            this.ywzj_rvp$armPreselectedZ = pos.z;
        }
    }
}
