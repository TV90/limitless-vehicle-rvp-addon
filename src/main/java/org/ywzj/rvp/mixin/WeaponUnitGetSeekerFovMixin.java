package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

/**
 * 修复 RVP 武器的 getSeekerFov() 返回值。
 * 本体只对 VehicleMissile 返回实际导引头 FOV，
 * RVP 武器走默认值 30°，导致 UI 大圈尺寸错误。
 * <p>
 * 用类名字符串比较替代 {@code instanceof}，避免 Connector 兼容问题。
 * </p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitGetSeekerFovMixin {

    private static final String RVP_WEAPON_BASE_NAME = "org.ywzj.rvp.weapon.core.RVP_WeaponBase";

    @Inject(method = "getSeekerFov", at = @At("RETURN"), cancellable = true, remap = false)
    private void ywzj_rvp$getSeekerFov(CallbackInfoReturnable<Float> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isEmpty()) return;
        AbstractVehicleWeapon<?> weapon = weaponOpt.get();
        // 用类名比较替代 instanceof（Connector 兼容）
        String clsName = weapon.getClass().getName();
        if (!clsName.equals(RVP_WEAPON_BASE_NAME)) {
            // 也可能是子类，检查父类/接口
            Class<?> c = weapon.getClass();
            boolean isRvpWeapon = false;
            while (c != null && !isRvpWeapon) {
                if (RVP_WEAPON_BASE_NAME.equals(c.getName())) {
                    isRvpWeapon = true;
                    break;
                }
                for (Class<?> iface : c.getInterfaces()) {
                    if (RVP_WEAPON_BASE_NAME.equals(iface.getName())) {
                        isRvpWeapon = true;
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if (!isRvpWeapon) return;
        }
        // 通过反射调用 getData().getMaxGuideHeadAngle()，避免 import
        try {
            Object data = weapon.getClass().getMethod("getData").invoke(weapon);
            if (data == null) return;
            float fov = (float) data.getClass().getMethod("getMaxGuideHeadAngle").invoke(data);
            if (fov > 0) cir.setReturnValue(fov);
        } catch (Exception ignored) {
        }
    }
}
