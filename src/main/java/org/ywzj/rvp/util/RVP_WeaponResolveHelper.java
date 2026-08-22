package org.ywzj.rvp.util;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

public final class RVP_WeaponResolveHelper {

    private RVP_WeaponResolveHelper() {}

    @Nullable
    public static AbstractVehicleWeapon<?> unwrap(@Nullable AbstractVehicleWeapon<?> weapon) {
        while (weapon != null) {
            if (weapon instanceof VehicleWeaponAgent agent) {
                weapon = agent.getWeaponUnit().getCurrentWeapon().orElse(null);
                continue;
            }
            if (weapon instanceof VehicleMultiWeapons multi) {
                weapon = multi.getSelectedWeapon();
                continue;
            }
            return weapon;
        }
        return null;
    }

    @Nullable
    public static AbstractVehicleWeapon<?> currentPrimary(WeaponUnit weaponUnit) {
        return weaponUnit == null ? null : unwrap(weaponUnit.getCurrentWeapon().orElse(null));
    }

    @Nullable
    public static RVP_WeaponBase currentPrimaryRvp(WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> weapon = currentPrimary(weaponUnit);
        return weapon instanceof RVP_WeaponBase rvp ? rvp : null;
    }

    /**
     * 解析武器级 {@code parent_weapon_unit_aim_override}：当前武器为 RVP 武器且配置了
     * 覆盖时返回该值；否则返回 null（调用方回退到所属武器站的静态配置）。
     * 传入武器建议先经 {@link #currentPrimary} 解包（Agent/Multi → 具体武器）。
     */
    @Nullable
    public static Boolean resolveParentWeaponUnitAimOverride(@Nullable AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof RVP_WeaponBase rvpWeapon) {
            return rvpWeapon.getData().getParentWeaponUnitAimOverride();
        }
        return null;
    }
}
