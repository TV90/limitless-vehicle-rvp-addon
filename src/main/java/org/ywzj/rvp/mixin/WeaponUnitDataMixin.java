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
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.ext.WeaponUnitPojoExt;
import org.ywzj.rvp.mixin.accessor.PartUnitDataAccessor;
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

    @Inject(method = "<init>(Lorg/ywzj/vehicle/custom/part/data/WeaponUnitPojo;)V", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$init(WeaponUnitPojo pojo, CallbackInfo ci) {
        if (pojo instanceof WeaponUnitPojoExt ext) {
            this.ywzj_rvp$fireControlMode = ext.ywzj_rvp$getFireControlMode();
            this.ywzj_rvp$rfOffAxisDeg = ext.ywzj_rvp$getRfOffAxisDeg();
            this.ywzj_rvp$disableCrtEffect = ext.ywzj_rvp$disableCrtEffect();
            this.ywzj_rvp$followParentOnlyPartUnitIds = ywzj_rvp$safeCopy(ext.ywzj_rvp$getFollowParentOnlyPartUnitIds());
            this.ywzj_rvp$structureBoltBones = ywzj_rvp$safeCopy(ext.ywzj_rvp$getStructureBoltBones());
            RVP_HitboxDebug.noteConfigLoaded(this.ywzj_rvp$followParentOnlyPartUnitIds);
        }
    }

    @Inject(method = "initStructureModel", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$initExtraBoltBones(BedrockModel model,
                                             Map<BedrockBone, VehicleCubeGroup> vehiclePartGroups,
                                             CallbackInfo ci) {
        if (model == null || ywzj_rvp$structureBoltBones.isEmpty()) {
            return;
        }
        BedrockBone firstBone = null;
        VehicleCubeGroup firstGroup = null;
        Vec3 firstPivot = null;
        List<Bolt> resolvedBolts = new ArrayList<>();
        List<VehicleCubeOBB> resolvedObbs = new ArrayList<>();
        for (String boneName : ywzj_rvp$structureBoltBones) {
            BedrockBone bone = model.getBoneMap().get(boneName);
            if (bone == null) {
                continue;
            }
            VehicleCubeGroup group = vehiclePartGroups.get(bone);
            if (group == null) {
                continue;
            }
            if (firstBone == null) {
                firstBone = bone;
                firstGroup = group;
                firstPivot = group.globalTransform().offset();
            }
            resolvedObbs.addAll(group.cubeOBBs);
            ywzj_rvp$appendBoltsFromBone(bone, Vec3.ZERO, group.globalTransform().offset().subtract(firstPivot), resolvedBolts);
        }
        if (firstGroup == null || resolvedBolts.isEmpty()) {
            return;
        }
        PartUnitDataAccessor accessor = (PartUnitDataAccessor) this;
        accessor.setStructureGroup(firstGroup);
        this.xTurnGroup = firstGroup;
        accessor.setPivotOffset(firstPivot);
        accessor.setPartCubeOBBs(resolvedObbs);
        this.bolts = resolvedBolts;
    }

    @Unique
    private void ywzj_rvp$appendBoltsFromBone(BedrockBone bone,
                                              Vec3 childOffset,
                                              Vec3 groupOffset,
                                              List<Bolt> out) {
        Vec3 accumulatedOffset = childOffset.add(groupOffset);
        for (BedrockCube cube : bone.cubes) {
            float x = cube.x() + cube.width() / 2;
            float y = cube.y() + cube.height() / 2;
            float z = cube.z();
            Vec3 boltOffset = new Vec3(bone.rotation.transform(new Vector3f(x, y, z))).add(accumulatedOffset);
            float barrelLength = cube.depth();
            Vector3f selfRot = new Vector3f();
            bone.rotation.getEulerAnglesYXZ(selfRot);
            out.add(new Bolt(
                    boltOffset,
                    barrelLength,
                    (float) Math.toDegrees(selfRot.x),
                    (float) Math.toDegrees(-selfRot.y)
            ));
        }
        for (BedrockBone child : bone.getChildren()) {
            ywzj_rvp$appendBoltsFromBone(
                    child,
                    accumulatedOffset.add(child.x / 16, child.y / 16, child.z / 16),
                    Vec3.ZERO,
                    out
            );
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
}
