package org.ywzj.rvp.config;

import com.mojang.math.Axis;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

import java.lang.reflect.Field;

/**
 * 发射架部署姿态助手。
 *
 * <p>发射架俯仰旋转组为本体 {@link WeaponUnit#xTurnGroup}（private，无公共 API；原
 * {@code WeaponUnitAccessor} 已为服务端安全删除）。这里用反射读取实例 xTurnGroup 恢复
 * 起竖/旋转行为（与 {@code RVP_CustomMountRenderLogic} 同款），反射不可用时退化为
 * structureGroup 近似（仅功能降级，不崩溃）。</p>
 */
public final class LauncherDeployPoseHelper {

    private static Field X_TURN_GROUP_FIELD;
    private static boolean X_TURN_GROUP_RESOLVED;

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

        // 命中发射架俯仰旋转组（xTurnGroup）：通过武器站 xRot 驱动，确保骨骼起竖/旋转
        if (partUnit instanceof WeaponUnit weaponUnit
                && targetGroup == resolveXTurnGroup(weaponUnit)) {
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
            VehicleCubeGroup xTurnGroup = resolveXTurnGroup(weaponUnit);
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

    /** 反射读取本体 {@code WeaponUnit.xTurnGroup}（private，无公共 API）。失败返回 null。 */
    @Nullable
    private static VehicleCubeGroup resolveXTurnGroup(WeaponUnit weaponUnit) {
        if (!X_TURN_GROUP_RESOLVED) {
            X_TURN_GROUP_RESOLVED = true;
            try {
                X_TURN_GROUP_FIELD = ObfuscationReflectionHelper.findField(WeaponUnit.class, "xTurnGroup");
                X_TURN_GROUP_FIELD.setAccessible(true);
            } catch (Throwable t) {
                X_TURN_GROUP_FIELD = null;
            }
        }
        if (X_TURN_GROUP_FIELD == null) {
            return null;
        }
        try {
            return (VehicleCubeGroup) X_TURN_GROUP_FIELD.get(weaponUnit);
        } catch (Throwable t) {
            return null;
        }
    }
}
