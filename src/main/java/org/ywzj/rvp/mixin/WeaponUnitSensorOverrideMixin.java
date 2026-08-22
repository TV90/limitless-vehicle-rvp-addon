package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

/**
 * 武器级传感器覆盖：当前 RVP 武器数据带 {@code fire_control_sensor_type_override} 时，
 * {@link WeaponUnit#getFireControlSensorType()} 直接返回覆盖值——站级静态传感器被"替换"
 * 而非与覆盖并存。使本体内部消费点（RF 自动锁 tick、IR/RF 导引头圈、锁定分支等）与
 * RVP 消费点（{@code RVP_WeaponSensorHelper}）看到同一个有效传感器。
 *
 * <p>安全说明：handler 只引用公共类（RVP_WeaponBase / RVP_WeaponData / 本体枚举），
 * 无任何 @OnlyIn(CLIENT) 类型；WeaponUnit 在公共数组已有 3 个既有 mixin（改脏状态
 * 与帧重算行为不变）。若当前武器非 RVP 或未配置覆盖，不干预返回值。</p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSensorOverrideMixin {

    @Inject(method = "getFireControlSensorType", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$overrideSensorTypeByCurrentWeapon(CallbackInfoReturnable<WeaponUnitData.FireControlSensorType> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isEmpty()) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = weaponOpt.get();
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        WeaponUnitData.FireControlSensorType override = data.getFireControlSensorTypeOverride();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
