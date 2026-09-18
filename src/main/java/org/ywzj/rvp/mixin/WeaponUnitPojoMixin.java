package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.config.RVP_SightFireDisguiseConfig;
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

    @SerializedName("rvp_optical_sight_pivot")
    @Unique
    private Vec3 ywzj_rvp$opticalSightPivot;

    /** 观瞄视角射弹原点分离配置（null = 未配置 = 功能关闭）。 */
    @SerializedName("rvp_sight_fire_disguise")
    @Unique
    private RVP_SightFireDisguiseConfig ywzj_rvp$sightFireDisguise;

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

    @Override
    public Vec3 ywzj_rvp$getOpticalSightPivot() {
        return ywzj_rvp$opticalSightPivot;
    }

    @Override
    public RVP_SightFireDisguiseConfig ywzj_rvp$getSightFireDisguise() {
        return ywzj_rvp$sightFireDisguise;
    }
}
