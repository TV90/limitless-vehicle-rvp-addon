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
 * 普通 {@code modding_only_multi} 弹种槽（如 LAV25 / ZBL08A）只能通过改装工具（{@code C2SSelectModdingSubWeapon}）切换，
 * 本体 F 键发送 {@code ClientVehicleSwitchWeapon(MULTI)} 后服务端调用 {@code cycleMultiWeapon}，
 * 该路径会绕过改装工具验证，需在此拦截并取消。
 * </p>
 * <p>
 * grouped slot carrier（如 T90M 主炮：AP 弹种组 modding_only_multi + HE 弹种 merge_into_previous_slot）：
 * 由 {@link RVP_VehicleExtendedConfigManager#shouldBlockCurrentMultiCycle} 判定为放行，
 * 不拦截，让本体 {@code cycleMultiWeapon} → 外层 {@code VehicleMultiWeapons.cycleSubWeapon} 完成 AP ↔ HE 大组切换；
 * 组内子武器仍由改装工具切换（改装工具走 {@code VehicleMultiWeapons.cycleSubWeapon}，不经 {@code cycleMultiWeapon}）。
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
    }
}
