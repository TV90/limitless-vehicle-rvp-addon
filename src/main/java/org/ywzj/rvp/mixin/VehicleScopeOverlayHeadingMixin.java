package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.ext.VehicleUIPresetAccessor;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 载具骨骼俯视图位置由该载具选择的 UI 预设控制。
 * <p>
 * 使用 {@code @Inject(HEAD)} 捕获载具引用，{@code @ModifyConstant} 替换偏移量。
 * 载具 JSON 中指定 {@code "ui_preset"} 选择预设。
 * 未指定时使用本体默认位置。
 * </p>
 */
@Mixin(value = VehicleScopeOverlay.class, remap = false)
public class VehicleScopeOverlayHeadingMixin {

    @Unique
    private AbstractVehicle ywzj_rvp$currentHeadingVehicle;

    @Inject(method = "renderVehicleHeading", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$captureVehicle(
            net.minecraft.client.gui.GuiGraphics guiGraphics,
            float partialTick, int screenWidth, int screenHeight,
            AbstractVehicle vehicle, CallbackInfo ci) {
        this.ywzj_rvp$currentHeadingVehicle = vehicle;
    }

    // ── 骨骼俯视图 X 偏移 ──
    // poseStack.translate(screenWidth / 2f + 116f, ...)
    @ModifyConstant(method = "renderVehicleHeading", constant = @Constant(floatValue = 116.0f), remap = false)
    private float ywzj_rvp$bonesXOffset(float original) {
        if (ywzj_rvp$currentHeadingVehicle instanceof VehicleUIPresetAccessor acc) {
            var pos = UIPresetManager.getVehicleBones(acc.ywzj_rvp$getUiPreset());
            if (pos != null) return (float) pos.offsetX;
        }
        return original;
    }

    // ── 骨骼俯视图 Y 偏移 ──
    // poseStack.translate(..., screenHeight / 2f + 80f, ...)
    @ModifyConstant(method = "renderVehicleHeading", constant = @Constant(floatValue = 80.0f), remap = false)
    private float ywzj_rvp$bonesYOffset(float original) {
        if (ywzj_rvp$currentHeadingVehicle instanceof VehicleUIPresetAccessor acc) {
            var pos = UIPresetManager.getVehicleBones(acc.ywzj_rvp$getUiPreset());
            if (pos != null) return (float) pos.offsetY;
        }
        return original;
    }
}
