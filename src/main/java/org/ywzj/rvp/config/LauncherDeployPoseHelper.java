package org.ywzj.rvp.config;

import com.mojang.math.Axis;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.mixin.accessor.WeaponUnitAccessor;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

public final class LauncherDeployPoseHelper {

    private LauncherDeployPoseHelper() {}

    public static void applyRuntimePitchForWeaponUnit(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return;
        }
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        if (vehicle == null || vehicle.getVehicleId() == null) {
            return;
        }
        RVP_LauncherDeployConfig config = findMatchingWeaponConfig(vehicle, weaponUnit);
        if (config == null || !config.applyPitchAfterWeaponTick()) {
            return;
        }
        LauncherDeployRuntimeManager.Snapshot snapshot = LauncherDeployRuntimeManager.get(vehicle.getId(), config.id());
        float pitch = snapshot != null ? snapshot.currentPitch() : config.stowedPitch();
        applyPitch(weaponUnit, config, pitch);
    }

    @Nullable
    public static RVP_LauncherDeployConfig findMatchingWeaponConfig(AbstractVehicle vehicle, WeaponUnit weaponUnit) {
        if (vehicle == null || weaponUnit == null || vehicle.getVehicleId() == null) {
            return null;
        }
        for (RVP_LauncherDeployConfig config : RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId())) {
            if (config.appliesToWeaponUnit(weaponUnit.getId())
                    || config.pitchPartUnitId().equals(weaponUnit.getId())) {
                return config;
            }
        }
        return null;
    }

    public static void applyPitch(PartUnit<?> partUnit, RVP_LauncherDeployConfig config, float pitch) {
        if (partUnit == null || config == null) {
            return;
        }

        VehicleCubeGroup targetGroup = resolvePitchGroup(partUnit, config.pitchGroup());
        if (targetGroup == null) {
            return;
        }

        if (partUnit instanceof WeaponUnit weaponUnit && targetGroup == ((WeaponUnitAccessor) weaponUnit).getXTurnGroup()) {
            weaponUnit.xRotO = weaponUnit.getXRot();
            weaponUnit.setXAimRot(pitch);
            weaponUnit.setXRot(pitch);
            weaponUnit.updateRot();
            return;
        }

        targetGroup.rotation = new Quaternionf(targetGroup.baseRotation).mul(Axis.XP.rotationDegrees(pitch));
    }

    @Nullable
    public static VehicleCubeGroup resolvePitchGroup(PartUnit<?> partUnit, String pitchGroupName) {
        if (partUnit instanceof WeaponUnit weaponUnit) {
            VehicleCubeGroup xTurnGroup = ((WeaponUnitAccessor) weaponUnit).getXTurnGroup();
            if (pitchGroupName == null || pitchGroupName.isBlank()) {
                return xTurnGroup != null ? xTurnGroup : partUnit.getStructureGroup();
            }
            PartUnitData data = ((PartUnitAccessorMixin) partUnit).ywzj_rvp$getData();
            String structureBone = data.getStructureBone();
            if (pitchGroupName.equals(structureBone + "_barrel") && xTurnGroup != null) {
                return xTurnGroup;
            }
            if (pitchGroupName.equals(structureBone)) {
                return partUnit.getStructureGroup();
            }
            if (xTurnGroup != null) {
                return xTurnGroup;
            }
        }

        if (pitchGroupName == null || pitchGroupName.isBlank()) {
            return partUnit.getStructureGroup();
        }
        PartUnitData data = ((PartUnitAccessorMixin) partUnit).ywzj_rvp$getData();
        if (pitchGroupName.equals(data.getStructureBone())) {
            return partUnit.getStructureGroup();
        }
        return null;
    }
}
