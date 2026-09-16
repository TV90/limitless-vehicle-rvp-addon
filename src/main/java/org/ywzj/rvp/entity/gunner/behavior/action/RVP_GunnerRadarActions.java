package org.ywzj.rvp.entity.gunner.behavior.action;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerLockDebug;
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
        if (weaponUnit == null) {
            return RVP_GunnerActionResult.INVALID;
        }
        if (target == null || !target.isAlive()) {
            // 目标消失或死亡时清除武器站内所有雷达锁，避免 RWR 和 SARH 中继保留幽灵目标。
            clearAllLocalLocks(weaponUnit);
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logLocalLock(vehicle, null, "NO_TARGET",
                        target == null ? "AI无tracked目标" : "target已死亡");
            }
            return RVP_GunnerActionResult.INVALID;
        }
        if (vehicle == null) {
            return RVP_GunnerActionResult.INVALID;
        }
        if (weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF
                && !hasRadarHomingWeaponForTarget(weaponUnit, target)) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logLocalLock(vehicle, target, "UNSUPPORTED", "sensor!=RF且无雷达制导武器");
            }
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        RadarUnit radar = prepareLockRadar(weaponUnit);
        if (radar == null) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logLocalLock(vehicle, target, "UNSUPPORTED", "无可锁雷达（radar==null）");
            }
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        Entity lockTarget = normalizeTarget(target);
        if (lockTarget == null || !lockTarget.isAlive()) {
            clearLocalLock(weaponUnit, radar);
            return RVP_GunnerActionResult.INVALID;
        }
        double maxRange = radar.getMaxScanDistance();
        double dist = radar.worldRadarPosition().distanceTo(lockTarget.position());
        if (dist > maxRange) {
            clearLocalLock(weaponUnit, radar);
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logLocalLock(vehicle, lockTarget, "RANGE",
                        String.format("dist=%.0f max=%.0f", dist, maxRange));
            }
            return RVP_GunnerActionResult.GATED;
        }
        // 方位扇区门：只判 y（方位）。本体雷达扫描/探测（RadarUnit.tickScan/tickDetect）与 RVP
        // 两条扫描链（RVP_RadarScanService / RVP_ClientRadarTickHandler）对俯仰均不设限，
        // rot_info 的 x_rot 仅是碟面动画钳制参数（phase 雷达 x_rot_speed=0 时碟面恒 0），
        // 不代表目标俯仰包线——2026-09-16 修复：原实现把 x_rot_min=0 当 MC 俯仰下限（负=向上），
        // 导致视轴上方目标（aimRot.x<0，即一切仰角目标）恒 GATED 清锁，gunner 贴脸/掠顶
        // 永远无法本地锁定；玩家手动锁走 ClientRadarAction.LOCK 不经过此门，故仅 gunner 复现。
        Vec2 aimRot = radar.aimRot(lockTarget.position());
        if (aimRot.y < radar.getYRotMin() || aimRot.y > radar.getYRotMax()) {
            clearLocalLock(weaponUnit, radar);
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logLocalLock(vehicle, lockTarget, "YAW",
                        String.format("aimY=%.1f lim=[%.0f,%.0f]", aimRot.y, radar.getYRotMin(), radar.getYRotMax()));
            }
            return RVP_GunnerActionResult.GATED;
        }
        // 调用本体雷达探测，刷新目标对应的雷达告警与探测状态。
        radar.detect(lockTarget);
        // 箔条禁锁期：目标被箔条干扰脱锁后短时间内不可被选中/锁定（仍可被扫描）.
        // 否则炮手 AI 每 tick 重锁会令脱锁瞬间被还原.
        if (RVP_ChaffJamState.isInCooldown(lockTarget.getUUID(), vehicle.level().getGameTime())) {
            clearLocalLock(weaponUnit, radar);
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logLocalLock(vehicle, lockTarget, "CHAFF", "");
            }
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
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RVP_GunnerLockDebug.logLocalLock(vehicle, lockTarget, "LOCKED",
                    String.format("dist=%.0f aimY=%.1f", dist, aimRot.y));
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

    /** 清除指定武器站的全部雷达锁及根武器站锁定目标。 */
    private static void clearAllLocalLocks(WeaponUnit weaponUnit) {
        // 调用本体雷达列表 API，枚举该武器站拥有的全部雷达部件，避免只清首选雷达。
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            // 调用本体雷达锁读取 API，跳过本来就没有锁定目标的雷达部件。
            if (radarUnit.getLockedEntity() != null) {
                // 调用本体雷达锁定 API，清除目标失效后留在各雷达部件上的锁。
                radarUnit.setLockedEntity(null);
            }
        }
        // 调用本体武器站层级 API，定位发射链实际读取锁定状态的 root 武器站。
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        // 调用本体根锁读取 API，仅在仍有锁时执行清理写入。
        if (root.getLockedEntity() != null) {
            // 调用本体武器站锁定 API，同步清理发射链读取的根武器站锁。
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
