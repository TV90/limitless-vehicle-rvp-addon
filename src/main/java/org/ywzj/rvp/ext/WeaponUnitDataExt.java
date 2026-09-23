package org.ywzj.rvp.ext;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.config.RVP_SightFireDisguiseConfig;

import java.util.List;

public interface WeaponUnitDataExt {
    /** 取得RVP武器站火控模式。 */
    String ywzj_rvp$getFireControlMode();

    /** 取得现有RF软火控离轴角，单位为度。 */
    float ywzj_rvp$getRfOffAxisDeg();

    /** 取得通用弹道提前量火控离轴角，单位为度。 */
    float ywzj_rvp$getFireControlOffAxisDeg();

    /** 查询是否关闭CRT后处理效果。 */
    boolean ywzj_rvp$disableCrtEffect();

    /** 取得仅跟随父部件姿态的部件ID列表。 */
    List<String> ywzj_rvp$getFollowParentOnlyPartUnitIds();

    /** 取得用于补充炮闩的结构骨骼名称列表。 */
    List<String> ywzj_rvp$getStructureBoltBones();
    /** 观瞄基准枢轴（渲染模型骨块 pivot，像素单位）；为 null 时退回武器站自身枢轴。 */
    Vec3 ywzj_rvp$getOpticalSightPivot();
    /** 观瞄视角射弹原点分离配置；为 null（JSON 未配置）时功能关闭。 */
    RVP_SightFireDisguiseConfig ywzj_rvp$getSightFireDisguise();
}
