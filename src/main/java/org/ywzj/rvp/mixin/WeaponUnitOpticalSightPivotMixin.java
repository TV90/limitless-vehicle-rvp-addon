package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

/**
 * 支持按部件配置观瞄基准枢轴（{@code rvp_optical_sight_pivot}，渲染模型骨块 pivot，像素单位）。
 *
 * 本体 {@code worldOpticalSightPosition} 默认以「武器站自身结构骨枢轴」为基准：
 * {@code pivotOffset + opticalSightOffset}，且随武器站自身旋转（selfRot）。
 * 本 Mixin 在配置了 {@code rvp_optical_sight_pivot} 时改为：
 * 1. 基准枢轴替换为配置值（如渲染模型 {@code guanmiao} 骨块的 pivot）；
 * 2. 位置只随父部件（如炮塔）旋转，不随武器站（如车长机枪）旋转，
 *    与渲染模型中"观瞄骨块绕自身枢轴旋转"的表现一致（机枪手观瞄挂在 guanmiao 上时适用）。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitOpticalSightPivotMixin {

    @Shadow
    private Vec3 opticalSightOffset;

    @Shadow
    private VehicleCubeGroup xTurnGroup;

    @Inject(method = "worldOpticalSightPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$applyOpticalSightPivot(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        WeaponUnitData data = self.getData();
        if (!(data instanceof WeaponUnitDataExt ext) || ext.ywzj_rvp$getOpticalSightPivot() == null) {
            return;
        }
        // 配置值为渲染模型骨块 pivot（像素），转方块单位作为观瞄基准枢轴
        Vec3 sightPivot = ext.ywzj_rvp$getOpticalSightPivot().scale(1.0 / 16.0);
        if (opticalSightOffset == null) {
            cir.setReturnValue(self.worldOwnerViewPosition(partialTick));
            return;
        }
        Vec3 offsetFromVehicle = sightPivot.add(opticalSightOffset);
        if (self.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR) {
            cir.setReturnValue(self.worldPositionWithGroupRot(offsetFromVehicle, xTurnGroup, partialTick));
            return;
        }
        cir.setReturnValue(self.worldPositionWithBaseRot(offsetFromVehicle, partialTick));
    }
}
