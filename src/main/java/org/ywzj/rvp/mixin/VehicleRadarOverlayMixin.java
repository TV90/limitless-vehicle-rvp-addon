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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
     * 扫描线角度修饰（HMD 捕获半视场 ping-pong / 数据侧 phase 扫描模式）。
     *
     * <p><b>2026-10-23 适配本体 27552d7（0.6.2）</b>：{@code drawScanLine} 是私有实例方法，
     * 重定向它时 handler <b>必须是非 static 的实例方法</b>，且首参显式声明接收者 {@code VehicleRadarOverlay self}
     * （写 static 会报 {@code 'static' modifier of handler method does not match target}，
     * 整个 mixin APPLY 失败并崩在 GuiOverlayManager.init）；
     * 而私有目标又无法用 {@code @Invoker} 桥（Mixin AP 对私有目标误报 could not locate）。
     * 因此本注入走实例 {@code @Redirect} + 显式 receiver，并把本体 drawScanLine 的方法体
     * 内联为 {@link #ywzj_rvp$drawScanLine}，用修饰后的角度自行绘制。
     * 内联来源：{@code VehicleRadarOverlay.drawScanLine}（0.6.2，L250 起）；本体若改该实现需同步。</p>
     */
    @Redirect(
            method = "renderInstruments",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/client/gui/VehicleRadarOverlay;drawScanLine(Lorg/joml/Matrix4f;IIFFFI)V"),
            remap = false
    )
    private void ywzj_rvp$modifyScanAngle(VehicleRadarOverlay self, Matrix4f matrix, int cx, int cy,
                                          float r, float angleDeg, float thickness, int color) {
        ywzj_rvp$drawScanLine(matrix, cx, cy, r, ywzj_rvp$resolveScanAngle(angleDeg), thickness, color);
    }

    /**
     * 计算扫描线角度：HMD 模式在共享捕获半视场内 ping-pong；否则当雷达数据侧为 phase 扫描模式时，
     * 按 {@code scan_period_tick} 周期性扫过整个视场（360° 时单向循环，其余 ping-pong）。
     * 其余情况原样返回本体算出的角度。
     */
    @Unique
    private static float ywzj_rvp$resolveScanAngle(float angleDeg) {
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        if (hmdState.isRadarHmd()) {
            RadarUnit radar = ywzj_rvp$currentRadarUnit;
            if (radar != null) {
                Vec3 radarPos = radar.worldRadarPosition();
                // 使用平滑角度（与 HMD 框位置一致）
                WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
                Vec3 aimDir = hmdState.resolveRadarAimDir(weaponUnit, radar);
                Vec3 radarToHead = radarPos.add(aimDir.scale(100)).subtract(radarPos);
                Vec2 localRot = radar.worldVecToLocalRot(radarToHead);
                float yMin = radar.getYRotMin();
                float yMax = radar.getYRotMax();
                if (yMax - yMin >= 360f) {
                    yMin = 0f;
                    yMax = 360f;
                }
                float center = Mth.clamp((float) localRot.y, yMin, yMax);
                // 在共享捕获视场内 ping-pong，5 tick 一个完整显示周期
                int tick = hmdState.getTickCount();
                float phase = ((tick % 5) + ywzj_rvp$partialTick) / 5.0f;
                phase = Mth.clamp(phase, 0f, 1f);
                float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
                float halfFov = RVP_ClientHmdState.RADAR_HMD_HALF_FOV_DEG;
                return center - halfFov + halfFov * 2.0f * pingPong;
            }
            return angleDeg;
        }

        RadarUnit radarUnit = ywzj_rvp$currentRadarUnit;
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
        float phase = ((radarUnit.getVehicle().tickCount % periodTick) + ywzj_rvp$partialTick) / periodTick;
        phase = Mth.clamp(phase, 0f, 1f);
        if (scanAz >= 360f) {
            return phase * 360f;
        }
        float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
        return yRotMin + scanAz * pingPong;
    }

    /**
     * 内联自本体 {@code VehicleRadarOverlay.drawScanLine}（0.6.2，L250 起）。
     *
     * <p>本体该方法是私有实例方法，mixin 无法直接调用也无法用 @Invoker 桥（AP 误报），
     * 故此处复制其绘制实现（QUADS 粗线）。若本体修改扫描线画法，这里需同步。</p>
     */
    @Unique
    private static void ywzj_rvp$drawScanLine(Matrix4f matrix, int cx, int cy, float r, float angleDeg,
                                              float thickness, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferbuilder = tesselator.getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float a = (float) (color >> 24 & 255) / 255.0F;
        float rCol = (float) (color >> 16 & 255) / 255.0F;
        float g = (float) (color >> 8 & 255) / 255.0F;
        float b = (float) (color & 255) / 255.0F;

        float rad = (float) Math.toRadians(angleDeg - 90);
        float dirX = (float) Math.cos(rad);
        float dirY = (float) Math.sin(rad);
        float perpX = (float) -Math.sin(rad);
        float perpY = (float) Math.cos(rad);
        float halfWidth = thickness / 2.0f;

        // A 圆心左偏移 | B 圆心右偏移 | C 终点右偏移 | D 终点左偏移
        bufferbuilder.vertex(matrix, cx - perpX * halfWidth, cy - perpY * halfWidth, 0).color(rCol, g, b, a).endVertex();
        bufferbuilder.vertex(matrix, cx + perpX * halfWidth, cy + perpY * halfWidth, 0).color(rCol, g, b, a).endVertex();
        bufferbuilder.vertex(matrix, cx + dirX * r + perpX * halfWidth, cy + dirY * r + perpY * halfWidth, 0).color(rCol, g, b, a).endVertex();
        bufferbuilder.vertex(matrix, cx + dirX * r - perpX * halfWidth, cy + dirY * r - perpY * halfWidth, 0).color(rCol, g, b, a).endVertex();

        tesselator.end();
        RenderSystem.disableBlend();
    }

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
