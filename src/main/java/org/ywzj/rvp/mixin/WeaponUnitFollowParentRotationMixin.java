package org.ywzj.rvp.mixin;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.debug.RVP_HitboxDebug;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

import java.util.List;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitFollowParentRotationMixin {

    @Inject(method = "updateRot", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$followParentOnlyParts(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!(self.getData() instanceof WeaponUnitDataExt ext)) {
            return;
        }
        if (!"rvp:abramsx".equals(String.valueOf(self.getVehicle().getVehicleId())) || !"turret".equals(self.getId())) {
            return;
        }
        List<String> ids = ext.ywzj_rvp$getFollowParentOnlyPartUnitIds();
        if (ids.isEmpty()) {
            RVP_HitboxDebug.noteEmptyConfig(self.getId());
            return;
        }
        RVP_HitboxDebug.noteUpdateEnter(self.getId(), ids);
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            self.getVehicle().getPartUnit(id).ifPresentOrElse(
                    partUnit -> ywzj_rvp$resetLocalRotation(partUnit),
                    () -> RVP_HitboxDebug.notePartMissing(id)
            );
        }
    }

    private static void ywzj_rvp$resetLocalRotation(PartUnit<?> partUnit) {
        VehicleCubeGroup group = partUnit.getStructureGroup();
        if (group == null || group.baseRotation == null) {
            RVP_HitboxDebug.noteGroupMissing(partUnit.getId());
            return;
        }
        String before = String.valueOf(group.rotation.getEulerAnglesYXZ(new org.joml.Vector3f()));
        group.rotation = new Quaternionf(group.baseRotation);
        RVP_HitboxDebug.noteReset(partUnit.getId(), before,
                String.valueOf(group.rotation.getEulerAnglesYXZ(new org.joml.Vector3f())));
    }
}
