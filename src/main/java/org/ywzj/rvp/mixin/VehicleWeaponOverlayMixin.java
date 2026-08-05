package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.client.gui.VehicleWeaponOverlay;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.Collections;
import java.util.List;

/**
 * 隐藏 modding_only_multi 类型多武器的 HUD 多弹种指示器（小圆点）。
 * 这类武器只能在改装工具中切换，不应在战斗 HUD 上显示切换 UI。
 */
@Mixin(value = VehicleWeaponOverlay.class, remap = false)
public class VehicleWeaponOverlayMixin {

    @Redirect(
            method = "renderCard",
            at = @At(value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/weapon/VehicleMultiWeapons;getSubWeapons()Ljava/util/List;"),
            require = 0
    )
    private List<AbstractVehicleWeapon<?>> ywzj_rvp$hideModdingOnlyMultiDots(VehicleMultiWeapons multi) {
        WeaponUnit weaponUnit = multi.getWeaponUnit();
        if (weaponUnit != null) {
            List<AbstractVehicleWeapon<?>> indexed = weaponUnit.getIndexedWeapons();
            for (int i = 0; i < indexed.size(); i++) {
                if (indexed.get(i) == multi
                        && RVP_VehicleExtendedConfigManager.INSTANCE.shouldBlockRuntimeMultiCycle(weaponUnit, i)) {
                    return Collections.emptyList();
                }
            }
        }
        return multi.getSubWeapons();
    }
}
