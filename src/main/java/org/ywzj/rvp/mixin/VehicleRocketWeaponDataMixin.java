package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.VehicleRocketWeaponDataExt;
import org.ywzj.vehicle.custom.weapon.data.VehicleRocketWeaponData;

@Mixin(value = VehicleRocketWeaponData.class, remap = false)
public class VehicleRocketWeaponDataMixin implements VehicleRocketWeaponDataExt {
    @SerializedName("ballistic_enabled")
    @Unique
    private boolean ywzj_rvp$ballisticEnabled = false;

    @SerializedName("ballistic_gravity")
    @Unique
    private float ywzj_rvp$ballisticGravity = 0.03f;

    @SerializedName("ballistic_drag")
    @Unique
    private float ywzj_rvp$ballisticDrag = 0.002f;

    @SerializedName("ballistic_prediction_tick")
    @Unique
    private int ywzj_rvp$ballisticPredictionTick = 240;

    @Override
    public boolean ywzj_rvp$isBallisticEnabled() {
        return ywzj_rvp$ballisticEnabled;
    }

    @Override
    public float ywzj_rvp$getBallisticGravity() {
        return ywzj_rvp$ballisticGravity;
    }

    @Override
    public float ywzj_rvp$getBallisticDrag() {
        return ywzj_rvp$ballisticDrag;
    }

    @Override
    public int ywzj_rvp$getBallisticPredictionTick() {
        return ywzj_rvp$ballisticPredictionTick;
    }
}
