package org.ywzj.rvp.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.vehicle.client.gui.VehicleRadarOverlay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 搜索雷达位置由 UI 预设控制。
 * <p>
 * 使用 {@code @ModifyArg} 替换 {@code VehicleRadarOverlay.render()} 中
 * {@code poseStack.translate(FFF)} 的坐标参数。
 * 所有注入点均使用 {@code require = 0}，在 Connector（Fabric 兼容）环境中
 * 若字节码不匹配则静默跳过，不影响游戏启动。
 * </p>
 */
@Mixin(value = VehicleRadarOverlay.class, remap = false)
public class VehicleRadarOverlayPositionMixin {

    @Unique
    private int ywzj_rvp$screenWidth;

    @Unique
    private int ywzj_rvp$screenHeight;

    /** 当前渲染的预设名称，从载具的 {@code ui_preset} 字段获取 */
    @Unique
    private String ywzj_rvp$presetName;

    /**
     * 预设中第一个雷达的 X 坐标。
     * 对于单雷达车辆（如 ps1sm），这是唯一的雷达圆心位置。
     * 对于多雷达，为回退位置（未单独配置时使用）。
     */
    @Unique
    private int ywzj_rvp$firstRadarX;

    /**
     * 预设中第一个雷达的 Y 坐标。
     */
    @Unique
    private int ywzj_rvp$firstRadarY;

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 0)
    private void ywzj_rvp$captureFrame(
            ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
            int screenWidth, int screenHeight, CallbackInfo ci) {
        this.ywzj_rvp$screenWidth = screenWidth;
        this.ywzj_rvp$screenHeight = screenHeight;
        this.ywzj_rvp$presetName = null;

        AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
        if (vehicle != null) {
            String cached = VehicleUIPresetCache.get(vehicle);
            if (cached != null && !cached.isEmpty()) this.ywzj_rvp$presetName = cached;
        }

        // 预计算第一个雷达位置
        UIPosition pos = null;
        if (this.ywzj_rvp$presetName != null && !this.ywzj_rvp$presetName.isEmpty()) {
            pos = UIPresetManager.getRadar(this.ywzj_rvp$presetName);
        }
        if (pos != null) {
            this.ywzj_rvp$firstRadarX = pos.computeX(screenWidth);
            this.ywzj_rvp$firstRadarY = pos.computeY(screenHeight);
        } else {
            // 回退到原始硬编码位置：screenWidth/2 + 128, screenHeight - 80
            this.ywzj_rvp$firstRadarX = screenWidth / 2 + 128;
            this.ywzj_rvp$firstRadarY = screenHeight - 80;
        }
    }

    // ========== 第一个 translate(FFF) — 定位首个雷达圆心 ==========

    @ModifyArg(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V",
                    ordinal = 0),
            index = 0, remap = false, require = 0)
    private float ywzj_rvp$firstRadarTranslateX(float original) {
        String preset = ywzj_rvp$presetName;
        if (preset == null || preset.isEmpty()) return original;
        // original = centerX - (radarUnits.size() - 1) * 32
        // 改为直接使用预设 X
        return ywzj_rvp$firstRadarX;
    }

    @ModifyArg(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V",
                    ordinal = 0),
            index = 1, remap = false, require = 0)
    private float ywzj_rvp$firstRadarTranslateY(float original) {
        String preset = ywzj_rvp$presetName;
        if (preset == null || preset.isEmpty()) return original;
        // original = centerY，改为直接使用预设 Y
        return ywzj_rvp$firstRadarY;
    }

    // ========== 后续 translate(FFF) — 多雷达间距 ==========
    // 单雷达车辆无此调用。对于多雷达，回退到原始 32px 间距。
    // 如需精确多雷达定位，需有 per-radar 索引追踪机制，暂不实现。
    // ordinal=1 的 translate 参数保持原始值不变（不覆写此方法）。
    // 这样多雷达车辆自动使用原始 32px 间距排列。
}
