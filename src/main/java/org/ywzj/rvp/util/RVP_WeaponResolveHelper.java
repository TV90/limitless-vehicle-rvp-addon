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

    /**
     * 当前选中的副武器（解包 Agent/Multi 代理后比较）。
     *
     * <p>与 {@link #currentPrimary} 同动机：{@code getCurrentSecondaryWeapon()} 返回的可能是
     * 包装对象，恒等比较会失效——蓄力类武器的开火键归属判定（{@code isFireKeyDown}）必须
     * 经此解包，否则主选中的 railgun 会把同轴机枪的副键输入当成自己的开火输入（2026-09-19
     * 同轴开火误驱动 railgun 蓄力回归的根因）。</p>
     */
    @Nullable
    public static AbstractVehicleWeapon<?> currentSecondary(WeaponUnit weaponUnit) {
        return weaponUnit == null ? null : unwrap(weaponUnit.getCurrentSecondaryWeapon().orElse(null));
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
