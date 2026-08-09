package org.ywzj.rvp.ext;

import net.minecraft.world.phys.Vec3;

import java.util.List;

public interface WeaponUnitDataExt {
    String ywzj_rvp$getFireControlMode();
    float ywzj_rvp$getRfOffAxisDeg();
    boolean ywzj_rvp$disableCrtEffect();
    List<String> ywzj_rvp$getFollowParentOnlyPartUnitIds();
    List<String> ywzj_rvp$getStructureBoltBones();
    /** 观瞄基准枢轴（渲染模型骨块 pivot，像素单位）；为 null 时退回武器站自身枢轴。 */
    Vec3 ywzj_rvp$getOpticalSightPivot();
}
