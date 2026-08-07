package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 拦截 F 键多弹种循环（MULTI_WEAPON_SWITCH）。
 * <p>
 * T90M / LAV25 等载具的 {@code modding_only_multi} 弹种槽只能通过改装工具（{@code C2SSelectModdingSubWeapon}）切换，
 * 本体 F 键发送 {@code ClientVehicleSwitchWeapon(MULTI)} 后服务端调用 {@code cycleMultiWeapon}，
 * 该路径会绕过改装工具验证，需在此拦截。
 * </p>
 * <p>
 * 改装工具切换走 {@code VehicleMultiWeapons.cycleSubWeapon}（不经 {@code cycleMultiWeapon}），不受影响。
 * </p>
 * <p>
 * grouped slot carrier（如 T90M 主炮：AP 弹种组 modding_only_multi + HE 弹种 merge_into_previous_slot）：
 * 拦截 MULTI 后重定向为武器槽切换（PRIMARY），使 F 键在 AP ↔ HE 大组间切换，组内子武器仍由改装工具选择。
 * </p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitMultiWeaponSwitchBlockMixin {

    @Inject(method = "cycleMultiWeapon", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$blockModdingOnlyMultiCycle(boolean next, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!RVP_VehicleExtendedConfigManager.INSTANCE.shouldBlockCurrentMultiCycle(self)) {
            return;
        }
        ci.cancel();
        if (RVP_VehicleExtendedConfigManager.INSTANCE.shouldRedirectCurrentMultiCycle(self)) {
            self.switchWeapon(false, next, false);
        }
    }
}
