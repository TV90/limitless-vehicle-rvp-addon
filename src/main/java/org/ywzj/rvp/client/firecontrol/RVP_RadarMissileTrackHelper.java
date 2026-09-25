package org.ywzj.rvp.client.firecontrol;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * {@code rvp_rf} 导弹相对雷达目标跟踪的客户端资格与目标解析器。
 *
 * <p>这里只认可本车雷达或外置雷达已经建立的硬锁，不把导引头/TWS 仅写入
 * {@link WeaponUnit#getLockedEntity()} 的软航迹升级成自动视线跟踪。</p>
 */
public final class RVP_RadarMissileTrackHelper {
    /** 工具类不允许实例化。 */
    private RVP_RadarMissileTrackHelper() {}

    /**
     * 判断当前武器是否为启用完整三态跟踪的 {@code rvp_rf} RVP 导弹。
     *
     * @param weaponUnit 当前根火控武器站
     * @return 当前武器、模式和有效传感器均满足条件时返回 {@code true}
     */
    public static boolean isEligible(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null || !(weaponUnit.getData() instanceof WeaponUnitDataExt ext)) {
            return false;
        }
        // 调用本项目武器解析器，兼容组合武器与子武器站委托后取得实际选中武器。
        RVP_WeaponBase weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(weaponUnit);
        boolean rvpMissile = weapon != null
                && weapon.getData().getWeaponKind() == RVP_EnumWeaponKind.MISSILE;
        // 调用本项目传感器解析器，使武器级传感器覆盖与三态资格使用同一口径。
        WeaponUnitData.FireControlSensorType sensorType = RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit);
        return RVP_BallisticLeadFireControlPolicy.supportsRfMissileTrackTrim(
                ext.ywzj_rvp$getFireControlMode(),
                sensorType,
                rvpMissile
        );
    }

    /**
     * 解析当前可供瞄准视线跟踪的雷达硬锁目标。
     *
     * @param weaponUnit 当前根火控武器站
     * @return 本车火控雷达硬锁优先，其次为外置雷达正式锁定；均不存在时返回 {@code null}
     */
    @Nullable
    public static Entity resolveHardLockedTarget(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        // 调用本项目雷达角色解析器，只读取实际 RadarUnit 上的硬锁，排除 WeaponUnit 软航迹。
        Entity localLocked = RVP_RadarRoleHelper.getLockedRadarEntity(root);
        if (localLocked != null && localLocked.isAlive()) {
            return localLocked;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        // 调用本项目外置雷达链路读取已由服务端确认的正式锁定，搜索航迹本身不会进入这里。
        Entity externalLocked = RVP_ExternalRadarLinkHelper.getClientLockedEntity(
                root.getVehicle(),
                minecraft.level.dimension().location()
        );
        return externalLocked != null && externalLocked.isAlive() ? externalLocked : null;
    }
}
