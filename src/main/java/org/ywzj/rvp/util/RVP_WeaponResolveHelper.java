package org.ywzj.rvp.util;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
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
     * 解析武器级 parent_weapon_unit_aim_override（弹着点/准心锚定方向）。
     *
     * <p>优先级：① 武器显式 parent_weapon_unit_aim_override（写了就以它为准，保证可预测）
     * → ② eo_ccip 动态语义：SCOPE 视角按"电视/激光指示"处理，弹着点锚定母武器站（准星随动）
     * → ③ 返回 null，由调用方回退武器站静态 parent_weapon_unit_aim。</p>
     *
     * <p>传入武器建议先经 {@link #currentPrimary} 解包（Agent/Multi → 具体武器）。</p>
     */
    @Nullable
    public static Boolean resolveParentWeaponUnitAimOverride(@Nullable AbstractVehicleWeapon<?> weapon) {
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return null;
        }
        // 调用本项目武器数据模型，读取覆盖配置与传感器模式。
        RVP_WeaponData data = rvpWeapon.getData();
        // ① 显式配置优先。
        Boolean explicit = data.getParentWeaponUnitAimOverride();
        if (explicit != null) {
            return explicit;
        }
        // ② eo_ccip + 开镜 ⇒ 锚定母武器站；经桥接查询避免公共代码引用客户端类型。
        if ("eo_ccip".equalsIgnoreCase(data.getFireControlSensorMode())
                && RVP_ClientActionsAccess.isLocalOperatorInScopeView()) {
            return Boolean.TRUE;
        }
        // ③ 回退武器站静态值。
        return null;
    }
}
