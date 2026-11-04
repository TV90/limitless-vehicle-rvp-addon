package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper;
import org.ywzj.vehicle.client.gui.VehicleStatusOverlay;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import java.util.List;
import java.util.Map;

/** 沿用已有俯视图颜色转发点：配置了模块时由 RVP 档位提供填充色，本体继续绘制几何与描边。 */
@Mixin(value = VehicleStatusOverlay.class, remap = false)
public class VehicleScopeOverlayHeadingFixMixin {
    @Shadow private static void addHeadingCube(List cubes, VehicleCubeOBB cube, Map rotations,
                                               float heading, float partialTick, int fillColor) {
        throw new AssertionError();
    }

    @Redirect(method = "renderVehicleHeading", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/gui/VehicleStatusOverlay;addHeadingCube(Ljava/util/List;Lorg/ywzj/vehicle/vehicle/structure/VehicleCubeOBB;Ljava/util/Map;FFI)V"))
    private static void ywzj_rvp$moduleDamageColor(List cubes, VehicleCubeOBB cube, Map rotations,
                                                  float heading, float partialTick, int fillColor) {
        // 调用统一模块配色；不新增注入点、不改本体 PartUnit 的战斗扣血规则。
        int color = RVP_DamagedPartWireframeHelper.headingFillColor(
                RVP_DamagedPartWireframeHelper.displayedVehicle(), cube, fillColor);
        addHeadingCube(cubes, cube, rotations, heading, partialTick, color);
    }
}
