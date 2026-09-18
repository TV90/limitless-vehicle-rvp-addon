package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockCube;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.debug.RVP_HitboxDebug;
import org.ywzj.rvp.config.RVP_SightFireDisguiseConfig;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.ext.WeaponUnitPojoExt;
import org.ywzj.rvp.mixin.accessor.PartUnitDataAccessor;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitPojo;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mixin(value = WeaponUnitData.class, remap = false)
public class WeaponUnitDataMixin implements WeaponUnitDataExt {
    @Shadow private List<Bolt> bolts;
    @Shadow private VehicleCubeGroup xTurnGroup;

    @Unique
    private String ywzj_rvp$fireControlMode = "";

    @Unique
    private float ywzj_rvp$rfOffAxisDeg = 10.0f;

    @Unique
    private boolean ywzj_rvp$disableCrtEffect;

    @Unique
    private List<String> ywzj_rvp$followParentOnlyPartUnitIds = List.of();

    @Unique
    private List<String> ywzj_rvp$structureBoltBones = List.of();

    @Unique
    private Vec3 ywzj_rvp$opticalSightPivot;

    @Unique
    private RVP_SightFireDisguiseConfig ywzj_rvp$sightFireDisguise;

    @Inject(method = "<init>(Lorg/ywzj/vehicle/custom/part/data/WeaponUnitPojo;)V", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$init(WeaponUnitPojo pojo, CallbackInfo ci) {
        if (pojo instanceof WeaponUnitPojoExt ext) {
            this.ywzj_rvp$fireControlMode = ext.ywzj_rvp$getFireControlMode();
            this.ywzj_rvp$rfOffAxisDeg = ext.ywzj_rvp$getRfOffAxisDeg();
            this.ywzj_rvp$disableCrtEffect = ext.ywzj_rvp$disableCrtEffect();
            this.ywzj_rvp$followParentOnlyPartUnitIds = ywzj_rvp$safeCopy(ext.ywzj_rvp$getFollowParentOnlyPartUnitIds());
            this.ywzj_rvp$structureBoltBones = ywzj_rvp$safeCopy(ext.ywzj_rvp$getStructureBoltBones());
            this.ywzj_rvp$opticalSightPivot = ext.ywzj_rvp$getOpticalSightPivot();
            this.ywzj_rvp$sightFireDisguise = ext.ywzj_rvp$getSightFireDisguise();
            RVP_HitboxDebug.noteConfigLoaded(this.ywzj_rvp$followParentOnlyPartUnitIds);
        }
    }

    @Inject(method = "initStructureModel", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$initExtraBoltBones(BedrockModel model,
                                             Map<BedrockBone, VehicleCubeGroup> vehiclePartGroups,
                                             CallbackInfo ci) {
        if (model == null || ywzj_rvp$structureBoltBones == null || ywzj_rvp$structureBoltBones.isEmpty()) {
            return;
        }
        Vec3 pivot = this.xTurnGroup != null ? this.xTurnGroup.globalTransform().offset() : Vec3.ZERO;
        // structureBone 定义在父类 PartUnitData，Mixin 无法 @Shadow 继承字段/方法，直接 cast 调用公共 getter
        String mainBoneName = ((PartUnitData) (Object) this).getStructureBone() == null ? ""
                : ((PartUnitData) (Object) this).getStructureBone() + "_barrel";
        List<Bolt> extra = new ArrayList<>();
        for (String boneName : ywzj_rvp$structureBoltBones) {
            if (boneName == null || boneName.isBlank() || boneName.equals(mainBoneName)) {
                // 主炮闩骨骼（structureBone + "_barrel"）已由本体 buildBolts 处理，跳过避免重复
                continue;
            }
            BedrockBone bone = model.getBoneMap().get(boneName);
            if (bone == null) {
                continue;
            }
            Vec3 groupDelta = Vec3.ZERO;
            VehicleCubeGroup group = vehiclePartGroups.get(bone);
            if (group != null) {
                // 额外发射骨骼相对主炮闩组原点的模型空间偏移（格单位）
                groupDelta = group.globalTransform().offset().subtract(pivot);
            }
            ywzj_rvp$appendBoltsFromBone(bone, groupDelta, extra);
        }
        if (extra.isEmpty()) {
            return;
        }
        List<Bolt> base = this.bolts == null ? List.of() : this.bolts;
        boolean baseIsDefaultFallback = base.size() == 1
                && Vec3.ZERO.equals(base.get(0).offset)
                && base.get(0).barrelLength == 0.0f;
        if (baseIsDefaultFallback) {
            // 本体只有"未配置炮闩"的兜底 bolt，直接以 rvp_structure_bolt_bones 重建
            this.bolts = extra;
        } else {
            List<Bolt> merged = new ArrayList<>(base);
            merged.addAll(extra);
            this.bolts = merged;
        }
    }

    /**
     * 与本体 {@code buildBolts} 等价：把发射骨骼的每个 Cube 视为一个炮管生成 bolt。
     * {@code childOffset} 为相对主炮闩组原点的初始偏移（格单位），子骨骼平移按像素/16 累加。
     */
    @Unique
    private void ywzj_rvp$appendBoltsFromBone(BedrockBone bone,
                                              Vec3 childOffset,
                                              List<Bolt> out) {
        for (BedrockCube cube : bone.cubes) {
            float x = cube.x() + cube.width() / 2;
            float y = cube.y() + cube.height() / 2;
            float z = cube.z();
            Vec3 boltOffset = new Vec3(bone.rotation.transform(new Vector3f(x, y, z)));
            boltOffset = boltOffset.add(childOffset);
            float barrelLength = cube.depth();
            Vector3f selfRot = new Vector3f();
            bone.rotation.getEulerAnglesYXZ(selfRot);
            out.add(new Bolt(boltOffset, barrelLength,
                    (float) Math.toDegrees(selfRot.x), (float) Math.toDegrees(-selfRot.y)));
        }
        for (BedrockBone child : bone.getChildren()) {
            ywzj_rvp$appendBoltsFromBone(child,
                    childOffset.add(child.x / 16, child.y / 16, child.z / 16), out);
        }
    }

    @Unique
    private static List<String> ywzj_rvp$safeCopy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

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
