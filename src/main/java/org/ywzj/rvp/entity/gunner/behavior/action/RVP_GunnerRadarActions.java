package org.ywzj.rvp.entity.gunner.behavior.action;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.GunnerExternalRadarController;
import org.ywzj.rvp.entity.gunner.ai.GunnerWeaponSuitability;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/** 封装 Gunner 对本车雷达、根武器站锁和外置雷达锁的写操作。 */
public final class RVP_GunnerRadarActions {

    RVP_GunnerRadarActions() {
    }

    /** 按现行顺序维护本车 RF 雷达锁。 */
    public RVP_GunnerActionResult maintainLocalLock(AbstractVehicle vehicle,
                                                    @Nullable WeaponUnit weaponUnit,
                                                    @Nullable Entity target) {
        if (vehicle == null || weaponUnit == null || target == null || !target.isAlive()) {
            return RVP_GunnerActionResult.INVALID;
        }
        if (weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF
                && !hasRadarHomingWeaponForTarget(weaponUnit, target)) {
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        RadarUnit radar = prepareLockRadar(weaponUnit);
        if (radar == null) {
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        Entity lockTarget = normalizeTarget(target);
        if (lockTarget == null || !lockTarget.isAlive()) {
            clearLocalLock(weaponUnit, radar);
            return RVP_GunnerActionResult.INVALID;
        }
        double maxRange = radar.getMaxScanDistance();
        if (radar.worldRadarPosition().distanceToSqr(lockTarget.position()) > maxRange * maxRange) {
            clearLocalLock(weaponUnit, radar);
            return RVP_GunnerActionResult.GATED;
        }
        Vec2 aimRot = radar.aimRot(lockTarget.position());
        if (aimRot.y < radar.getYRotMin() || aimRot.y > radar.getYRotMax()
                || aimRot.x < radar.getXRotMin() || aimRot.x > radar.getXRotMax()) {
            clearLocalLock(weaponUnit, radar);
            return RVP_GunnerActionResult.GATED;
        }
        // 调用本体雷达探测，刷新目标对应的雷达告警与探测状态。
        radar.detect(lockTarget);
        // 箔条禁锁期：目标被箔条干扰脱锁后短时间内不可被选中/锁定（仍可被扫描）.
        // 否则炮手 AI 每 tick 重锁会令脱锁瞬间被还原.
        if (RVP_ChaffJamState.isInCooldown(lockTarget.getUUID(), vehicle.level().getGameTime())) {
            clearLocalLock(weaponUnit, radar);
            return RVP_GunnerActionResult.GATED;
        }
        if (radar.getLockedEntity() != lockTarget) {
            // 调用本体雷达锁定 API，写入通过射界与箔条门控的目标。
            radar.setLockedEntity(lockTarget);
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root.getLockedEntity() != lockTarget) {
            // 调用本体根武器站锁定 API，使武器发射链读取同一目标。
            root.setLockedEntity(lockTarget);
        }
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** 维持外置雷达中继、requested/locked 状态及其清理。 */
    public RVP_GunnerActionResult maintainExternalLock(GunnerEntity gunner,
                                                       AbstractVehicle vehicle,
                                                       @Nullable WeaponUnit weaponUnit,
                                                       @Nullable Entity target,
                                                       boolean driverAi) {
        if (gunner == null || vehicle == null) {
            return RVP_GunnerActionResult.INVALID;
        }
        if (!driverAi || vehicle.level().isClientSide() || weaponUnit == null) {
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        // 调用项目既有外置雷达控制器，维持中继部署、探测、锁定和失效清理。
        GunnerExternalRadarController.tick(gunner, vehicle, weaponUnit, target, driverAi);
        return RVP_GunnerActionResult.EXECUTED;
    }

    @Nullable
    private static RadarUnit prepareLockRadar(WeaponUnit weaponUnit) {
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!radarUnit.isOn()) {
                // 调用本体雷达开关 API，为 Gunner 的 RF 锁定准备传感器。
                radarUnit.toggle(true);
            }
        }
        return RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit);
    }

    private static void clearLocalLock(WeaponUnit weaponUnit, RadarUnit radar) {
        if (radar.getLockedEntity() != null) {
            radar.setLockedEntity(null);
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root.getLockedEntity() != null) {
            root.setLockedEntity(null);
        }
    }

    @Nullable
    private static Entity normalizeTarget(Entity target) {
        if (target instanceof Player player) {
            return player.getVehicle() instanceof AbstractVehicle targetVehicle ? targetVehicle : player;
        }
        return target instanceof AbstractVehicle ? target : null;
    }

    /** 判断武器组是否包含可攻击当前目标的雷达制导武器。 */
    private static boolean hasRadarHomingWeaponForTarget(WeaponUnit weaponUnit, Entity target) {
        for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
            AbstractVehicleWeapon<?> proxyWeapon = weaponUnit.proxyWeapon(weapon);
            if (!(proxyWeapon instanceof RVP_WeaponBase rvpWeapon)) {
                continue;
            }
            RVP_WeaponData data = rvpWeapon.getData();
            if (data != null && data.isRadarHoming()
                    && GunnerWeaponSuitability.canSelectForTarget(weaponUnit, weapon, target)) {
                return true;
            }
        }
        return false;
    }
}
