package org.ywzj.rvp.util;

import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;

import java.util.Optional;

/**
 * 判断当前武器是否有寻的头，以及寻的头类型（用于决定在 HUD 上画哪种导引头圈）。
 * <ul>
 *   <li>IR → 红外大圈</li>
 *   <li>SARH / ARH / ARM → 雷达小双圈</li>
 *   <li>无寻的头（SACLOS / MCLOS / IOG / GPS 等）→ 不画圈</li>
 * </ul>
 */
public class RVP_SeekerHelper {

    /** 有对应导引头圈的寻的制导类型。 */
    public enum SeekerType {
        /** 红外导引头 → 大圈 */
        INFRARED,
        /** 雷达导引头（SARH/ARH）或反辐射（ARM）→ 小双圈 */
        RADAR,
        /** 无寻的头，不画圈 */
        NONE
    }

    public static SeekerType resolveSeekerType(WeaponUnit weaponUnit) {
        if (!weaponUnit.isSeekerOn()) return SeekerType.NONE;

        Optional<AbstractVehicleWeapon<?>> currentOpt = weaponUnit.getCurrentWeapon();
        if (!currentOpt.isPresent()) return SeekerType.NONE;

        AbstractVehicleWeapon<?> weapon = currentOpt.get();

        // === rvp:missile (RVP_WeaponBase) ===
        if (weapon instanceof RVP_WeaponBase) {
            RVP_WeaponData data = ((RVP_WeaponBase) weapon).getData();
            if (data.getWeaponKind() != org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MISSILE) {
                return SeekerType.NONE;
            }
            if (data.usesGuidanceType(RVP_EnumGuidanceType.IR)) {
                return SeekerType.INFRARED;
            }
            if (data.isRadarHoming() || data.isAntiRadiationMissile()) {
                return SeekerType.RADAR;
            }
            return SeekerType.NONE;
        }

        // === 本体 missile (VehicleMissile) ===
        if (weapon instanceof VehicleMissile) {
            VehicleMissileWeaponData data = ((VehicleMissile) weapon).getData();
            if (data.getGuidance() != VehicleMissileWeaponData.Guidance.HOMING) {
                return SeekerType.NONE;
            }
            switch (data.getHomingMode()) {
                case INFRARED:
                case ELECTRO_OPTICAL:
                    return SeekerType.INFRARED;
                case SEMI_ACTIVE_RADAR:
                case ACTIVE_RADAR:
                    return SeekerType.RADAR;
                default:
                    return SeekerType.NONE;
            }
        }

        return SeekerType.NONE;
    }
}
