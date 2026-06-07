package org.ywzj.rvp.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * HITL MOUSE/VIEW: pin scope crosshair to screen centre.
 * HITL DESIGNATE: custom crosshair drawn by {@link org.ywzj.rvp.client.gui.RVP_HitlDesignateOverlay}.
 */
@Mixin(value = VehicleScopeOverlay.class, remap = false)
public class VehicleScopeOverlayMixin {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$skipScopeCrosshairInDesignate(GuiGraphics guiGraphics, float partialTick,
                                                        AbstractVehicle vehicle, CallbackInfo ci) {
        if (ywzj_rvp$isHitlDesignateScopeView()) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "renderCrosshair",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/util/VectorUtil;worldToScreen(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            remap = false
    )
    private Vec3 ywzj_rvp$lockCrosshairToCenter(Vec3 pos) {
        if (!ywzj_rvp$pinCrosshairToCenter()) {
            return VectorUtil.worldToScreen(pos);
        }
        Minecraft mc = Minecraft.getInstance();
        double cx = mc.getWindow().getGuiScaledWidth() / 2.0;
        double cy = mc.getWindow().getGuiScaledHeight() / 2.0;
        return new Vec3(cx, cy, 0.0);
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$ensureHitlCrosshairVisible(GuiGraphics guiGraphics, float partialTick,
                                                   AbstractVehicle vehicle, CallbackInfo ci) {
        if (!ywzj_rvp$pinCrosshairToCenter()) {
            return;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp.weaponHitPos == null) {
            Minecraft mc = Minecraft.getInstance();
            double cx = mc.getWindow().getGuiScaledWidth() / 2.0;
            double cy = mc.getWindow().getGuiScaledHeight() / 2.0;
            Vec3 centre = new Vec3(cx, cy, 0.0);
            lvp.weaponHitPos = centre;
            lvp.weaponHitPosO = centre;
        }
    }

    @Inject(method = "renderWeaponEngagementEnvelope", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$hideEngagementEnvelopeInHitl(GuiGraphics guiGraphics, float partialTick,
                                                         int screenWidth, int screenHeight,
                                                         AbstractVehicle vehicle, CallbackInfo ci) {
        if (ywzj_rvp$isHitlScopeView()) {
            ci.cancel();
        }
    }

    private static boolean ywzj_rvp$isHitlScopeView() {
        if (!RVP_ClientHitlState.isActive()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.options.getCameraType().isFirstPerson();
    }

    private static boolean ywzj_rvp$isHitlDesignateScopeView() {
        return ywzj_rvp$isHitlScopeView() && RVP_ClientHitlState.isDesignateMode();
    }

    private static boolean ywzj_rvp$pinCrosshairToCenter() {
        return ywzj_rvp$isHitlScopeView() && !RVP_ClientHitlState.isDesignateMode();
    }
}
