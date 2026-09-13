package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
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
            Object kind = data.getClass().getMethod("getWeaponKind").invoke(data);
            if (kind != RVP_EnumWeaponKind.MISSILE) {
                return;
            }
            // [RVP] 目的：ARM 反辐射导弹的导引头大圈由 RVP_MissileOverlay 自渲染（离轴圈），
            // 本体 VehicleAimAtOverlay 的大圈半径 = 2.8 × getSeekerFov()——ARM 返回 0 使本体
            // 大圈不可见（防 sensor=rf 站上本体圈与 RVP 自渲染圈叠加；全工程唯一消费方即该行）。
            Object guidance = data.getClass().getMethod("getGuidanceData").invoke(data);
            Object guidanceType = guidance.getClass().getMethod("getGuidanceType").invoke(guidance);
            if (guidanceType != null && "ARM".equals(guidanceType.toString())) {
                cir.setReturnValue(0f);
                return;
            }
            float fov = (float) data.getClass().getMethod("resolveLaunchOffAxisLockAngle").invoke(data);
            if (fov > 0) cir.setReturnValue(fov);
        } catch (Exception ignored) {
        }
    }
}
