package org.ywzj.rvp.config;

import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.slf4j.Logger;
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

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Field X_TURN_GROUP_FIELD;
    private static boolean X_TURN_GROUP_RESOLVED;
    private static boolean X_TURN_GROUP_OK_LOGGED;

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
            LOGGER.info("[RVP-LaunchDeploy] applyPitch 未解析到分组 part={} pitchGroup={} pitch={}",
                    partUnit.getId(), config.pitchGroup(), pitch);
            return;
        }

        // 发射架武器站：始终驱动 xRot 让 xTurnGroup（发射架臂/导弹渲染组）旋转，
        // 并直接写 structureGroup.rotation（防止本体 updateRot 把臂重置为 baseRotation）。
        // 旧代码靠 WeaponUnitLauncherDeployPoseBypassMixin（weapon tick TAIL）保证姿态不被覆盖，
        // 非 mixin 下这里双管齐下覆盖两种渲染路径（PartUnit 武器模型 + 载具 body 模型骨）。
        if (partUnit instanceof WeaponUnit weaponUnit) {
            LOGGER.info("[RVP-LaunchDeploy] 驱动 xRot + 写 structureGroup part={} pitch={} xTurnGroup={}",
                    partUnit.getId(), pitch, resolveXTurnGroup(weaponUnit));
            weaponUnit.xRotO = weaponUnit.getXRot();
            weaponUnit.setXAimRot(pitch);
            weaponUnit.setXRot(pitch);
            weaponUnit.updateRot();
            if (targetGroup == weaponUnit.getStructureGroup()) {
                targetGroup.rotation = new Quaternionf(targetGroup.baseRotation).mul(Axis.XP.rotationDegrees(pitch));
            }
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
                LOGGER.info("[RVP-LaunchDeploy] xTurnGroup 反射字段解析成功: {}", X_TURN_GROUP_FIELD);
            } catch (Throwable t) {
                LOGGER.warn("[RVP-LaunchDeploy] xTurnGroup 反射字段解析失败: {}", t.toString());
                X_TURN_GROUP_FIELD = null;
            }
        }
        if (X_TURN_GROUP_FIELD == null) {
            return null;
        }
        try {
            return (VehicleCubeGroup) X_TURN_GROUP_FIELD.get(weaponUnit);
        } catch (Throwable t) {
            LOGGER.debug("[RVP-LaunchDeploy] 读取 xTurnGroup 失败", t);
            return null;
        }
    }
}
