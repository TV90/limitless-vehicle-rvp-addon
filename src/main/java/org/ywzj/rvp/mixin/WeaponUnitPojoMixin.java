package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.WeaponUnitPojoExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitPojo;

import java.util.List;

@Mixin(value = WeaponUnitPojo.class, remap = false)
public class WeaponUnitPojoMixin implements WeaponUnitPojoExt {
    @SerializedName("rvp_fire_control_mode")
    @Unique
    private String ywzj_rvp$fireControlMode = "";

    @SerializedName("rvp_rf_off_axis_deg")
    @Unique
    private float ywzj_rvp$rfOffAxisDeg = 10.0f;

    @SerializedName("rvp_disable_crt_effect")
    @Unique
    private boolean ywzj_rvp$disableCrtEffect;

    @SerializedName("rvp_follow_parent_only_part_unit_ids")
    @Unique
    private List<String> ywzj_rvp$followParentOnlyPartUnitIds = List.of();

    @SerializedName("rvp_structure_bolt_bones")
    @Unique
    private List<String> ywzj_rvp$structureBoltBones = List.of();

    @Override
    public String ywzj_rvp$getFireControlMode() {
        return ywzj_rvp$fireControlMode;
    }

    @Override
    public float ywzj_rvp$getRfOffAxisDeg() {
        return ywzj_rvp$rfOffAxisDeg;
    }

    @Override
    public boolean ywzj_rvp$disableCrtEffect() {
        return ywzj_rvp$disableCrtEffect;
    }

    @Override
    public List<String> ywzj_rvp$getFollowParentOnlyPartUnitIds() {
        return ywzj_rvp$followParentOnlyPartUnitIds;
    }

    @Override
    public List<String> ywzj_rvp$getStructureBoltBones() {
        return ywzj_rvp$structureBoltBones;
    }
}
