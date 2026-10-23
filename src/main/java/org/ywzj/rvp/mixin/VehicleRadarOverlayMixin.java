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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.client.gui.VehicleRadarOverlay;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = VehicleRadarOverlay.class, remap = false)
public class VehicleRadarOverlayMixin {

    @Unique
    private static RadarUnit ywzj_rvp$currentRadarUnit;

    @Unique
    private static float ywzj_rvp$partialTick;

    @Unique
    private static final float ywzj_rvp$RWR_SCALE = 1.3f;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$captureFrame(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, CallbackInfo ci) {
        ywzj_rvp$partialTick = partialTick;
        ywzj_rvp$currentRadarUnit = null;
    }

    @Inject(
            method = "renderInstruments",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
                    ordinal = 0,
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
                            target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFFF)V",
                            ordinal = 0
                    )
            ),
            require = 0,
            remap = false
    )
    private void ywzj_rvp$scaleRwrDial(GuiGraphics guiGraphics, float partialTick, WeaponUnit weaponUnit, float centerX, float radarY, CallbackInfo ci) {
        guiGraphics.pose().scale(ywzj_rvp$RWR_SCALE, ywzj_rvp$RWR_SCALE, ywzj_rvp$RWR_SCALE);
    }

    @Redirect(
            method = "renderInstruments",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/RadarUnit;isOn()Z"),
            remap = false
    )
    private boolean ywzj_rvp$captureRadarUnit(RadarUnit radarUnit) {
        ywzj_rvp$currentRadarUnit = radarUnit;
        return radarUnit.isOn();
    }

    @Redirect(
            method = "renderInstruments",
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

    /**
     * [挂起 2026-10-24] 扫描线角度修饰（HMD 捕获半视场 ping-pong / 数据侧 phase 扫描模式）
     * 暂未随本体 27552d7 适配：drawScanLine 为私有实例方法，其 invokespecial 重定向要求
     * static handler + 显式接收者，private 调用需 @Invoker 桥（注解处理器对私有目标误报
     * could not locate，编译被拦）。两项子功能现状：phase 扫描模式当前零武器配置；
     * HMD 扫描线摆动仅影响雷达面板扫描线的摆动方式。恢复方案：@Redirect static+receiver
     * （已验证到 handler 校验一步）+ @Invoker 桥绕过 AP 误报（如改为抽象类形式或关 AP 校验）。
     */

    @Unique
    private static RadarUnitDataExt ywzj_rvp$getRadarDataExt(RadarUnit radarUnit) {
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext;
        }
        return null;
    }

    /**
     * 多雷达固定间距：原代码 translate(-radius * sin(yRotMax) * 1.3) 因不同雷达
     * yRotMax 值差异（如 180°→sin=0 vs 45°→sin≈0.85）导致间距不一致。
     * 通过判断 a > 10（drawRotatedText 中 sin 参数是弧度值 < 6，translate 中是角度值 > 10）
     * 拦截 translate 中的 Math.sin 调用，固定返回 sin(75°) 对应的弧度值 1.309，
     * 使间距恒为 ~-1.256*radius。
     */
    /**
     * 多雷达固定间距：原代码 translate(-radius * sin(yRotMax) * 1.3) 因不同雷达
     * yRotMax 值差异（如 180°→sin=0 vs 45°→sin≈0.85）导致间距不一致。
     * 2026-10-23 适配本体 27552d7：@ModifyArg 在重构后的 renderInstruments 上无法绑定，
     * 改为 @Redirect 包裹该 Math.sin 调用（方法内唯一一处）：角度值 > 10 时固定返回
     * sin(75°) 对应弧度 1.309，使间距恒为 ~-1.256*radius。
     */
    @Redirect(
            method = "renderInstruments",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;sin(D)D"),
            remap = false
    )
    private double ywzj_rvp$fixRadarSpacing(double a) {
        if (a > 10.0) {
            // Math.toRadians(75°) ≈ 1.309 → Math.sin(1.309) ≈ 0.966
            return 1.309;
        }
        return Math.sin(a);
    }

    @Inject(
            method = "renderInstruments",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
                    ordinal = 2,
                    shift = At.Shift.BEFORE
            ),
            remap = false,
            require = 0
    )
    private void ywzj_rvp$renderHmdSectorInRadarSpace(GuiGraphics guiGraphics, float partialTick, WeaponUnit weaponUnit, float centerX, float radarY, CallbackInfo ci) {
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        if (!hmdState.isRadarHmd()) return;
        RadarUnit radar = ywzj_rvp$currentRadarUnit;
        if (radar == null) return;

        RadarUnitData data = radar.getData();
        if (!(data instanceof RadarUnitDataExt ext) || !ext.ywzj_rvp$isEnableHms()) return;

        if (weaponUnit == null) weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) return;
        int radarCount = weaponUnit.getRadarUnits().size();
        if (radarCount <= 0) return;
        float radius = radarCount == 1 ? 50.0f : 50.0f / radarCount / 0.6f;

        // 使用平滑角度（同 HMD 框位置）
        RVP_ClientHmdState hmd = RVP_ClientHmdState.getInstance();
        Vec3 aimDir = hmd.resolveRadarAimDir(weaponUnit, radar);

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

        float halfFov = RVP_ClientHmdState.RADAR_HMD_HALF_FOV_DEG;
        float sectorStart = hmdBearing - halfFov;
        float sectorEnd = hmdBearing + halfFov;

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

        buf.vertex(matrix, 0, 0, 0).color(rCol, gCol, bCol, a).endVertex();
        int segs = 8;
        for (int i = 0; i <= segs; i++) {
            float angle = sectorStart + (sectorEnd - sectorStart) * ((float) i / segs);
            float rad = -(float) Math.toRadians(angle + 90);
            float vx = (float) Math.cos(rad) * radius;
            float vy = (float) Math.sin(rad) * radius;
            buf.vertex(matrix, vx, vy, 0).color(rCol, gCol, bCol, a).endVertex();
        }
        tess.end();
        RenderSystem.disableBlend();
    }
}
