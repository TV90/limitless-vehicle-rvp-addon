package org.ywzj.rvp.entity.gunner.ai;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.firecontrol.RVP_BallisticLeadFireControlPolicy;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.guidance.RVP_CommandGuidanceAim;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/** 按 Gunner 实际控制的武器解析双端安全的 STABLE 火控资格。 */
public final class RVP_GunnerFireControlPolicy {

    private RVP_GunnerFireControlPolicy() {}

    /** 判断本次选中的实际 RVP 机炮能否由 Gunner 使用 STABLE 弹道预瞄。 */
    public static boolean supportsStableMachinegun(WeaponUnit weaponUnit,
                                                   @Nullable AbstractVehicleWeapon<?> rawWeapon) {
        // 调用本项目代理解包器，读取本次选弹对应的实际 RVP 武器数据。
        RVP_WeaponData data = selectedRvpData(weaponUnit, rawWeapon);
        // 调用本项目数据模型读取武器类型，确保仅 RVP 机炮进入弹道预瞄。
        if (data == null || data.getWeaponKind() != RVP_EnumWeaponKind.MACHINEGUN) {
            return false;
        }
        return supportsStabilizer(weaponUnit, data, true, false);
    }

    /** 判断本次选中的实际 SACLOS 导弹是否具备 RF STABLE PIP 火控资格。 */
    public static boolean supportsStableSaclos(WeaponUnit weaponUnit,
                                               @Nullable AbstractVehicleWeapon<?> rawWeapon) {
        // 调用本项目武器代理解析，避免用武器站手动选中项代替 Gunner 受控索引。
        return supportsStableSaclos(weaponUnit, selectedRvpData(weaponUnit, rawWeapon));
    }

    /** 判断指定在途 RVP 弹药数据是否符合其发射武器站的 SACLOS STABLE PIP 火控资格。 */
    public static boolean supportsStableSaclos(WeaponUnit weaponUnit,
                                               @Nullable RVP_WeaponData data) {
        // 调用本项目武器数据接口，要求在途弹确为 SACLOS 制导导弹。
        if (data == null
                || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE
                || !data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)) {
            return false;
        }
        return supportsStabilizer(weaponUnit, data, false, true);
    }

    /** 按实际武器站配置、武器数据覆盖和现有火控策略统一判断 STABLE 资格。 */
    private static boolean supportsStabilizer(WeaponUnit weaponUnit,
                                              RVP_WeaponData data,
                                              boolean machinegun,
                                              boolean missile) {
        // 调用本项目指令瞄准站解析，把子武器站映射到承载火控配置的根站。
        WeaponUnit aimUnit = RVP_CommandGuidanceAim.resolveOperatorAimUnit(weaponUnit);
        // 调用本体站点数据接口，确认存在本项目扩展的火控模式配置。
        if (aimUnit == null || !(aimUnit.getData() instanceof WeaponUnitDataExt ext)) {
            return false;
        }
        // 调用本项目双端传感器解析器，按当前 Gunner 实际武器数据应用动态覆盖。
        WeaponUnitData.FireControlSensorType sensorType =
                RVP_WeaponSensorHelper.effectiveOrStatic(aimUnit, data);
        // 调用现有 STABLE 资格策略，保持 rvp_rf 与 rvp_ballistic_lead 的模式边界一致。
        return RVP_BallisticLeadFireControlPolicy.supportsStabilizer(
                ext.ywzj_rvp$getFireControlMode(), sensorType, machinegun, missile);
    }

    /** 解包本次 Gunner 选中的武器，并返回 RVP 数据；非 RVP 武器返回 null。 */
    @Nullable
    private static RVP_WeaponData selectedRvpData(WeaponUnit weaponUnit,
                                                  @Nullable AbstractVehicleWeapon<?> rawWeapon) {
        if (weaponUnit == null || rawWeapon == null) {
            return null;
        }
        // 调用本体武器站代理解析，取得 Agent/Multi 当前实际参与发射的武器。
        AbstractVehicleWeapon<?> proxyWeapon = weaponUnit.proxyWeapon(rawWeapon);
        // 调用本项目统一解包器，兼容代理链并确认最终 RVP 武器类型。
        AbstractVehicleWeapon<?> selectedWeapon = RVP_WeaponResolveHelper.unwrap(proxyWeapon);
        if (!(selectedWeapon instanceof RVP_WeaponBase rvpWeapon)) {
            return null;
        }
        // 调用本项目武器数据访问器，返回选中 RVP 武器的数据模型。
        return rvpWeapon.getData();
    }
}
