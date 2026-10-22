package org.ywzj.rvp.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.gui.RVP_WeaponPanelMetrics;
import org.ywzj.vehicle.client.gui.VehicleWeaponOverlay;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * 抓取本体武器栏本帧的分隔线纵坐标，让 RVP 右侧辅助 HUD（过热/蓄力等）能与新 HUD 对齐。
 *
 * <p>本体把「弹药读出区」画在分隔线上方、弹种行画在下方（{@code VehicleWeaponOverlay:128}），
 * 但这条坐标只存在于局部变量里。RVP 原先按屏幕像素自己估算位置，适配新 HUD 后就压到读出区上了。
 * 这里在 {@code drawSeparator} 首次非纵向调用时记下 y，RVP 直接把文案顶在读出区上方。</p>
 *
 * <p>全部 {@code require = 0}：本体改签名时静默失效，过热栏回退到默认位置，不会崩。</p>
 */
@Mixin(value = VehicleWeaponOverlay.class, remap = false)
public class VehicleWeaponOverlayMetricsMixin {

    @Inject(method = "render", at = @At("HEAD"), require = 0, remap = false)
    private void ywzj_rvp$beginWeaponPanelFrame(ForgeGui gui, GuiGraphics graphics, float partialTick,
                                               int screenWidth, int screenHeight, CallbackInfo ci) {
        RVP_WeaponPanelMetrics.beginFrame();
    }

    @Inject(method = "drawSeparator", at = @At("HEAD"), require = 0, remap = false)
    private void ywzj_rvp$captureSeparator(GuiGraphics graphics, float x, float y, float length,
                                          boolean vertical, CallbackInfo ci) {
        if (!vertical) {
            RVP_WeaponPanelMetrics.captureSeparator(y);
            RVP_WeaponPanelMetrics.captureExtent(x, length);
        }
    }

    /** 读出区高度随副武器条数变化，抓 {@code renderReloadArea} 的 {@code top} 取最小值＝整块读出区顶。 */
    @Inject(method = "renderReloadArea", at = @At("HEAD"), require = 0, remap = false)
    private void ywzj_rvp$captureReadoutTop(GuiGraphics graphics, AbstractVehicleWeapon<?> weapon,
                                            float left, float top, float right, float bottom,
                                            float partialTick, CallbackInfo ci) {
        RVP_WeaponPanelMetrics.captureReadoutTop(top);
    }
}
