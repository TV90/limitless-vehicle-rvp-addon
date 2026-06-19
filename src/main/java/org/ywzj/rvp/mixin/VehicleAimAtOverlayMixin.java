package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.util.RVP_SeekerHelper;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 修复导引头圈显示：
 * 当前武器是 IR 弹 → 显示红外大圈
 * 当前武器是 SARH/ARH/ARM 弹 → 显示雷达小双圈
 * 当前武器是 SACLOS/MCLOS/IOG/GPS 等 → 不画圈
 */
@Mixin(value = VehicleAimAtOverlay.class, remap = false)
public class VehicleAimAtOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;isSeekerOn()Z"),
            remap = false
    )
    private boolean ywzj_rvp$seekerOnByHomingMode(WeaponUnit weaponUnit) {
        RVP_SeekerHelper.SeekerType type = RVP_SeekerHelper.resolveSeekerType(weaponUnit);
        return type != RVP_SeekerHelper.SeekerType.NONE;
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;getFireControlSensorType()Lorg/ywzj/vehicle/custom/part/data/WeaponUnitData$FireControlSensorType;"),
            remap = false
    )
    private WeaponUnitData.FireControlSensorType ywzj_rvp$sensorTypeByHomingMode(WeaponUnit weaponUnit) {
        RVP_SeekerHelper.SeekerType type = RVP_SeekerHelper.resolveSeekerType(weaponUnit);
        switch (type) {
            case INFRARED:
                return WeaponUnitData.FireControlSensorType.IR;
            case RADAR:
                return WeaponUnitData.FireControlSensorType.RF;
            default:
                return WeaponUnitData.FireControlSensorType.NONE;
        }
    }
}
