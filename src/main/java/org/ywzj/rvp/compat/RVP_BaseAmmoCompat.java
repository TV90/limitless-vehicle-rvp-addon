package org.ywzj.rvp.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ecm.RVP_EcmIff;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AerialBombEntity;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;

/**
 * 本体（{@code ywzj_vehicle}）弹药联动工具。
 *
 * <p>本体导弹的制导目标均为公开字段（{@code targetEntity}/{@code targetPos}），
 * 主动雷达弹另有 {@code activeRadarOn}/{@code activeRadarCatch} 公开标志位。
 * RVP 的 ECM/DIRCM 直接改写这些字段即可让本体弹失效，不需要新增 mixin。</p>
 */
public final class RVP_BaseAmmoCompat {

    private RVP_BaseAmmoCompat() {
    }

    /** 是否为本体战术地图需要显示的弹药（导弹/火箭弹/航空炸弹）。 */
    public static boolean isOrdnance(Entity entity) {
        return entity instanceof MissileEntity
                || entity instanceof RocketEntity
                || entity instanceof AerialBombEntity;
    }

    /** 读取本体导弹的武器数据；非本体弹或无配置返回 null。 */
    @Nullable
    public static VehicleMissileWeaponData missileData(MissileEntity missile) {
        if (missile == null) {
            return null;
        }
        ResourceLocation weaponId = missile.getWeaponId();
        if (weaponId == null) {
            return null;
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data())
                .filter(VehicleMissileWeaponData.class::isInstance)
                .map(VehicleMissileWeaponData.class::cast)
                .orElse(null);
    }

    /** 本体导弹是否为红外制导（DIRCM 可干扰）。 */
    public static boolean isInfraredHoming(MissileEntity missile) {
        VehicleMissileWeaponData data = missileData(missile);
        return data != null
                && data.getGuidance() == VehicleMissileWeaponData.Guidance.HOMING
                && data.getHomingMode() == VehicleMissileWeaponData.HomingMode.INFRARED;
    }

    /** 本体导弹是否为主动雷达制导（主动ECM 可干扰）。 */
    public static boolean isActiveRadarHoming(MissileEntity missile) {
        VehicleMissileWeaponData data = missileData(missile);
        return data != null
                && data.getGuidance() == VehicleMissileWeaponData.Guidance.HOMING
                && data.getHomingMode() == VehicleMissileWeaponData.HomingMode.ACTIVE_RADAR;
    }

    /**
     * 本体弹药对指定载具是否友方/己方：同发射载具、同乘客、同队伍或同 gunner 阵营。
     * 语义与 {@code RVP_DircmRuntimeManager.isFriendlyProjectile} 对齐。
     */
    public static boolean isFriendly(AbstractVehicle vehicle, AmmoEntity ammo) {
        if (vehicle == null || ammo == null) {
            return false;
        }
        if (ammo.vehicle == vehicle || ammo.vehicle != null
                && ammo.vehicle.getUUID().equals(vehicle.getUUID())) {
            return true;
        }
        Entity owner = ammo.getOwner();
        if (owner == vehicle || vehicle.getPassengers().contains(owner)) {
            return true;
        }
        if (ammo.vehicle != null && RVP_EcmIff.areVehiclesFriendly(vehicle, ammo.vehicle)) {
            return true;
        }
        Team vehicleTeam = vehicle.getTeam();
        Team ownerTeam = owner != null ? owner.getTeam() : null;
        return vehicleTeam != null && ownerTeam != null
                && (ownerTeam.isAlliedTo(vehicleTeam) || vehicleTeam.isAlliedTo(ownerTeam));
    }

    /**
     * 致盲本体红外弹：清空当前目标与落点。
     * 本体 IR 制导在 {@code targetEntity == null} 时直接返回，不会重新截获。
     */
    public static void blindInfrared(MissileEntity missile) {
        if (missile == null) {
            return;
        }
        missile.targetEntity = null;
        missile.targetPos = null;
    }

    /**
     * 瘫痪本体主动雷达弹：关闭弹载雷达并清空目标。
     * 本体主动雷达的重新截获只在 {@code activeRadarOn == true} 时执行，
     * 关闭后即使目标仍在扫描范围内也不会再次上锁。
     */
    public static void disableActiveRadar(MissileEntity missile) {
        if (missile == null) {
            return;
        }
        missile.activeRadarOn = false;
        missile.activeRadarCatch = false;
        missile.activeRadarLostTargetTick = 0;
        missile.targetEntity = null;
        missile.targetPos = null;
    }
}
