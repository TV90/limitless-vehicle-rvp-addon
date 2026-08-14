package org.ywzj.rvp.config;

import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

import java.lang.reflect.Field;

/**
 * 发射架部署诊断助手。
 *
 * <p>发射架俯仰旋转组为本体 {@link WeaponUnit#xTurnGroup}（private，无公共 API）。这里用
 * 反射读取实例 xTurnGroup，仅用于诊断日志输出当前绕 X 旋转角；实际俯仰由
 * {@code LauncherDeployStateMachine#driveLauncherPitch} 通过驱动部件 {@code xRot/xAimRot}
 * （本体公共 API）实现。</p>
 */
public final class LauncherDeployPoseHelper {

    private static Field X_TURN_GROUP_FIELD;
    private static boolean X_TURN_GROUP_RESOLVED;

    private LauncherDeployPoseHelper() {}

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

    /** 诊断用：返回武器站 xTurnGroup 的当前绕 X 旋转角（度）；不可用返回 NaN。 */
    public static float getXTurnGroupAngleDeg(WeaponUnit weaponUnit) {
        VehicleCubeGroup g = resolveXTurnGroup(weaponUnit);
        if (g == null || g.rotation == null) {
            return Float.NaN;
        }
        org.joml.Vector3f euler = new org.joml.Vector3f();
        g.rotation.getEulerAnglesXYZ(euler);
        return (float) Math.toDegrees(euler.x);
    }
}
