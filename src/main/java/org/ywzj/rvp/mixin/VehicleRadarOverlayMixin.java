package org.ywzj.rvp.mixin;

import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.client.gui.VehicleRadarOverlay;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

@Mixin(value = VehicleRadarOverlay.class, remap = false)
public class VehicleRadarOverlayMixin {

    @Unique
    private RadarUnit ywzj_rvp$currentRadarUnit;

    @Unique
    private float ywzj_rvp$partialTick;

    @Unique
    private static final float ywzj_rvp$RWR_SCALE = 1.3f;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$captureFrame(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        this.ywzj_rvp$partialTick = partialTick;
        this.ywzj_rvp$currentRadarUnit = null;
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            ),
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lorg/ywzj/vehicle/entity/vehicle/AbstractVehicle;warningReceiver:Lorg/ywzj/vehicle/vehicle/passenger/WarningReceiver;",
                            ordinal = 0
                    ),
                    to = @At(
                            value = "INVOKE",
                            target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFIIFII)V",
                            ordinal = 0
                    )
            ),
            require = 0,
            remap = false
    )
    private void ywzj_rvp$scaleRwrDial(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        guiGraphics.pose().scale(ywzj_rvp$RWR_SCALE, ywzj_rvp$RWR_SCALE, ywzj_rvp$RWR_SCALE);
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/RadarUnit;isOn()Z"),
            remap = false
    )
    private boolean ywzj_rvp$captureRadarUnit(RadarUnit radarUnit) {
        this.ywzj_rvp$currentRadarUnit = radarUnit;
        return radarUnit.isOn();
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/RadarUnit;getLockedEntity()Lnet/minecraft/world/entity/Entity;", ordinal = 0),
            remap = false
    )
    private Entity ywzj_rvp$scanLineWhenLocked(RadarUnit radarUnit) {
        RadarUnitDataExt ext = ywzj_rvp$getRadarDataExt(radarUnit);
        if (ext != null && ext.ywzj_rvp$isScanLineWhenLocked()) {
            return null;
        }
        return radarUnit.getLockedEntity();
    }

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/client/gui/VehicleRadarOverlay;drawScanLine(Lorg/joml/Matrix4f;IIFFFI)V"),
            index = 4,
            remap = false
    )
    private float ywzj_rvp$modifyScanAngle(float angleDeg) {
        RadarUnit radarUnit = this.ywzj_rvp$currentRadarUnit;
        if (radarUnit == null) {
            return angleDeg;
        }
        RadarUnitDataExt ext = ywzj_rvp$getRadarDataExt(radarUnit);
        if (ext == null) {
            return angleDeg;
        }
        if (!"phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode())) {
            return angleDeg;
        }
        int periodTick = ext.ywzj_rvp$getScanPeriodTick();
        if (periodTick <= 0) {
            return angleDeg;
        }
        float yRotMin = radarUnit.getYRotMin();
        float yRotMax = radarUnit.getYRotMax();
        float scanAz = yRotMax - yRotMin;
        if (scanAz >= 360f) {
            yRotMin = 0f;
            scanAz = 360f;
        }
        float phase = ((radarUnit.getVehicle().tickCount % periodTick) + this.ywzj_rvp$partialTick) / periodTick;
        phase = Mth.clamp(phase, 0f, 1f);
        if (scanAz >= 360f) {
            return phase * 360f;
        }
        float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
        return yRotMin + scanAz * pingPong;
    }

    @Unique
    private static RadarUnitDataExt ywzj_rvp$getRadarDataExt(RadarUnit radarUnit) {
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext;
        }
        return null;
    }
}
