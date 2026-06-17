package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.client.gui.VehicleRadarOverlay;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;

@Mixin(value = VehicleRadarOverlay.class, remap = false)
public class VehicleRadarOverlayMixin {

    @Unique
    private RadarUnit ywzj_rvp$currentRadarUnit;

    @Unique
    private float ywzj_rvp$partialTick;

    @Unique
    private static final float ywzj_rvp$RWR_SCALE = 1.3f;

    @Unique
    private int ywzj_rvp$hmdCenterX;

    @Unique
    private int ywzj_rvp$hmdCenterY;

    @Unique
    private float ywzj_rvp$hmdRadius;

    @Unique
    private float ywzj_rvp$hmdYRotMin;

    @Unique
    private int ywzj_rvp$hmdYRotMax;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$captureFrame(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        this.ywzj_rvp$partialTick = partialTick;
        this.ywzj_rvp$currentRadarUnit = null;
        // 存储雷达小地图位置，供 HMD 扇形绘制使用
        this.ywzj_rvp$hmdCenterX = screenWidth / 2 + 128;
        this.ywzj_rvp$hmdCenterY = screenHeight - 80;
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
        // HMD 模式：扫描线只在 3° 小扇形内摆动，频率加快（每 5 tick 一个周期）
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        if (hmdState.isRadarHmd()) {
            RadarUnit radar = this.ywzj_rvp$currentRadarUnit;
            if (radar != null) {
                Vec3 radarPos = radar.worldRadarPosition();
                // 使用平滑角度（同 HMD 框位置）
                RVP_ClientHmdState hmd = RVP_ClientHmdState.getInstance();
                Vec3 aimDir = VectorUtil.rotToVec(hmd.getSmoothPitch(), hmd.getSmoothYaw()).normalize();
                Vec3 radarToHead = radarPos.add(aimDir.scale(100)).subtract(radarPos);
                Vec2 localRot = radar.worldVecToLocalRot(radarToHead);
                float yMin = radar.getYRotMin();
                float yMax = radar.getYRotMax();
                if (yMax - yMin >= 360f) {
                    yMin = 0f;
                    yMax = 360f;
                }
                float center = Mth.clamp((float) localRot.y, yMin, yMax);
                // 在 3° 内 ping-pong，每 5 tick 一个完整周期
                int tick = hmd.getTickCount();
                float phase = ((tick % 5) + this.ywzj_rvp$partialTick) / 5.0f;
                phase = Mth.clamp(phase, 0f, 1f);
                float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
                return center - 1.5f + 3.0f * pingPong;
            }
            return angleDeg;
        }

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

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$renderHmdSector(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        if (!hmdState.isHmdMode()) {
            return;
        }
        // IR HMD 模式：不需要在雷达上画扇形
        if (hmdState.isIrHmd()) {
            return;
        }
        RadarUnit radar = this.ywzj_rvp$currentRadarUnit;
        if (radar == null) {
            return;
        }

        // 使用平滑角度（同 HMD 框位置）
        RVP_ClientHmdState hmd = RVP_ClientHmdState.getInstance();
        Vec3 aimDir = VectorUtil.rotToVec(hmd.getSmoothPitch(), hmd.getSmoothYaw()).normalize();

        Vec3 radarPos = radar.worldRadarPosition();
        Vec3 radarToHead = radarPos.add(aimDir.scale(100)).subtract(radarPos);
        Vec2 localRot = radar.worldVecToLocalRot(radarToHead);

        float yMin = radar.getYRotMin();
        float yMax = radar.getYRotMax();
        if (yMax - yMin >= 360f) {
            yMin = 0f;
            yMax = 360f;
        }
        // 取反 localRot.y：worldVecToLocalRot 与 drawRadarSector 的角方向相反
        float hmdBearing = Mth.clamp(-(float) localRot.y, -yMax, -yMin);

        int cx = this.ywzj_rvp$hmdCenterX;
        int cy = this.ywzj_rvp$hmdCenterY;
        float r = 50f;

        float sectorStart = hmdBearing - 1.5f;
        float sectorEnd = hmdBearing + 1.5f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        int sectorColor = Color.RADAR_SECTOR;
        float a = (float)(sectorColor >> 24 & 255) / 255.0F;
        float rCol = (float)(sectorColor >> 16 & 255) / 255.0F;
        float gCol = (float)(sectorColor >> 8 & 255) / 255.0F;
        float bCol = (float)(sectorColor & 255) / 255.0F;

        Matrix4f matrix = guiGraphics.pose().last().pose();

        buf.vertex(matrix, (float) cx, (float) cy, 0).color(rCol, gCol, bCol, a).endVertex();
        int segs = 8;
        for (int i = 0; i <= segs; i++) {
            float angle = sectorStart + (sectorEnd - sectorStart) * ((float) i / segs);
            float rad = -(float) Math.toRadians(angle + 90);
            float vx = cx + (float) Math.cos(rad) * r;
            float vy = cy + (float) Math.sin(rad) * r;
            buf.vertex(matrix, vx, vy, 0).color(rCol, gCol, bCol, a).endVertex();
        }
        tess.end();
        RenderSystem.disableBlend();
    }
}
