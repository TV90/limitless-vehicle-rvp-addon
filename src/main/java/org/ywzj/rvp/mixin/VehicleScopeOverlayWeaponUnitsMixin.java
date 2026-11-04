package org.ywzj.rvp.mixin;

import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.client.gui.VehicleStatusOverlay;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * RVP 载具的武器站骨骼照画进机身朝向图。
 *
 * <p>2026-10-23 适配本体 27552d7：renderVehicleHeading 自 VehicleScopeOverlay 迁入
 * VehicleStatusOverlay（新 HUD 状态面板）私有静态方法，本 Mixin 原地重锚；语义不变——
 * 显式配置了 RVP 骨骼模块的载具把 {@code WeaponUnit.isSeat()} 视作 true，使非座椅武器站
 * （炮塔等）的 OBB 与子武器站骨骼不因座椅过滤被跳过。类名沿用历史，锚点已迁。</p>
 */
@Mixin(value = VehicleStatusOverlay.class, remap = false)
public class VehicleScopeOverlayWeaponUnitsMixin {

    @Redirect(
            method = "renderVehicleHeading",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;isSeat()Z"),
            remap = false
    )
    private static boolean ywzj_rvp$showWeaponUnitsInBones(WeaponUnit weaponUnit) {
        // 只按显式模块配置开放武器站线框，不向未配置的本体载具扩散显示规则。
        if (!RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(weaponUnit.getVehicle()).isEmpty()) return true;
        return weaponUnit.isSeat();
    }
}
