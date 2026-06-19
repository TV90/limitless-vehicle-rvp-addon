package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.util.RVP_SeekerHelper;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 修复 BVR/望远镜模式下导引头圈显示逻辑。
 * <p>
 * 注意：仅拦截 {@code isSeekerOn()}，不拦截 {@code getFireControlSensorType()}，
 * 因为后者被缓存为局部变量 sensorType，用于雷达锁定框的判断。
 * sensorType 应使用 JSON 中配置的原始值（如 {@code "fire_control_sensor_type": "rf"}）。
 * </p>
 */
@Mixin(value = VehicleScopeOverlay.class, remap = false)
public class VehicleScopeOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;isSeekerOn()Z"),
            remap = false
    )
    private boolean ywzj_rvp$seekerOnByHomingMode(WeaponUnit weaponUnit) {
        RVP_SeekerHelper.SeekerType type = RVP_SeekerHelper.resolveSeekerType(weaponUnit);
        return type != RVP_SeekerHelper.SeekerType.NONE;
    }
}
