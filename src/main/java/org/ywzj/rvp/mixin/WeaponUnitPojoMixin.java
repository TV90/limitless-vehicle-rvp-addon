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
    /** 武器站RVP火控模式；默认空字符串表示完全使用本体火控。 */
    @SerializedName("rvp_fire_control_mode")
    @Unique
    private String ywzj_rvp$fireControlMode = "";

    /** 现有 {@code rvp_rf} 模式离轴角，单位为度，仅在RF软火控生效。
     *  <p>用 Float 而非 float：Gson 反序列化 pojo 走 Unsafe 分配，字段初始化器不执行，
     *  原始类型"未配置"会落 0 而非注释声称的 10；null 可穿透分配，由 getter 兜底默认。 */
    @SerializedName("rvp_rf_off_axis_deg")
    @Unique
    private Float ywzj_rvp$rfOffAxisDeg;

    /** 通用弹道提前量模式离轴角，单位为度，仅在 {@code rvp_ballistic_lead} 生效。
     *  <p>Float 兜底理由同上；显式配置 0 仍保留"退回目标中心跟踪"语义。 */
    @SerializedName("rvp_fire_control_off_axis_deg")
    @Unique
    private Float ywzj_rvp$fireControlOffAxisDeg;

    /** 是否关闭该武器站的CRT后处理效果；默认false。 */
    @SerializedName("rvp_disable_crt_effect")
    @Unique
    private boolean ywzj_rvp$disableCrtEffect;

    /** 仅跟随父部件姿态的部件ID列表；默认空列表。 */
    @SerializedName("rvp_follow_parent_only_part_unit_ids")
    @Unique
    private List<String> ywzj_rvp$followParentOnlyPartUnitIds = List.of();

    /** 用于补充炮闩的结构骨骼名称列表；默认空列表。 */
    @SerializedName("rvp_structure_bolt_bones")
    @Unique
    private List<String> ywzj_rvp$structureBoltBones = List.of();

    /** 观瞄基准枢轴，单位为模型像素；未配置时为null并回退武器站自身枢轴。 */
    @SerializedName("rvp_optical_sight_pivot")
    @Unique
    private Vec3 ywzj_rvp$opticalSightPivot;

    /** 观瞄视角射弹原点分离配置（null = 未配置 = 功能关闭）。 */
    @SerializedName("rvp_sight_fire_disguise")
    @Unique
    private RVP_SightFireDisguiseConfig ywzj_rvp$sightFireDisguise;

    @Override
    public String ywzj_rvp$getFireControlMode() {
        // Gson Unsafe 分配下 String 字段"未配置"实际为 null，兜底空串（= 完全使用本体火控）
        return ywzj_rvp$fireControlMode == null ? "" : ywzj_rvp$fireControlMode;
    }

    @Override
    public float ywzj_rvp$getRfOffAxisDeg() {
        return ywzj_rvp$rfOffAxisDeg == null ? 10.0f : ywzj_rvp$rfOffAxisDeg;
    }

    @Override
    public float ywzj_rvp$getFireControlOffAxisDeg() {
        return ywzj_rvp$fireControlOffAxisDeg == null ? 10.0f : ywzj_rvp$fireControlOffAxisDeg;
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
