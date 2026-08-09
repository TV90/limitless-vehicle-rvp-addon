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
 * <p>
 * 例外：grouped slot carrier（如 T90M 主炮 AP 组 + HE 组 {@code merge_into_previous_slot}），
 * 装配器已把 AP/HE 合并为外层 multi 且 F 键可在外层直接切换弹种，此时圆点应显示以提示玩家。
 * </p>
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
                if (indexed.get(i) != multi) {
                    continue;
                }
                if (RVP_VehicleExtendedConfigManager.INSTANCE.isGroupedSlotCarrier(
                        weaponUnit.getVehicle(), weaponUnit.getId(), i)) {
                    // 外层 multi（AP+HE 合并槽）：F 键可切换弹种，保留圆点提示。
                    return multi.getSubWeapons();
                }
                if (RVP_VehicleExtendedConfigManager.INSTANCE.shouldBlockRuntimeMultiCycle(weaponUnit, i)) {
                    return Collections.emptyList();
                }
            }
        }
        return multi.getSubWeapons();
    }
}
