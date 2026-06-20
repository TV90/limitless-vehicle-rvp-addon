package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.apache.commons.lang3.StringUtils;
import org.joml.Matrix4f;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.List;
import java.util.Map;

/**
 * 雷达 + RWR 覆盖层。
 * <p>
 * 复制自 {@code VehicleRadarOverlay}，仅修改位置和缩放计算以使用 {@link UIPresetManager}。
 * 渲染逻辑、颜色、动画完全保留不动。
 * </p>
 *
 * <h3>与原版的差异（标记为 {@code // [RVP]}）</h3>
 * <ul>
 *   <li>雷达圆心位置 → 读 {@code preset.radar}</li>
 *   <li>雷达半径 → 乘以 {@code pos.scale}</li>
 *   <li>RWR 中心 → 读 {@code preset.rwr}</li>
 *   <li>RWR 缩放 → {@code poseStack.scale(rwrPos.scale)}</li>
 * </ul>
 */
public class RVP_RadarOverlay implements IGuiOverlay {

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        String presetName = vehicle.getVehicleId() != null ? VehicleUIPresetCache.get(vehicle.getVehicleId()) : null;
        UIPosition radarPos = UIPresetManager.getRadar(presetName);
        UIPosition rwrPos = UIPresetManager.getRwr(presetName);

        int centerX = radarPos != null ? radarPos.computeX(screenWidth) : screenWidth / 2 + 128;
        int centerY = radarPos != null ? radarPos.computeY(screenHeight) : screenHeight - 80;
        float radarScale = radarPos != null ? radarPos.scale : 1.0f;
        int rwrCenterX = rwrPos != null ? rwrPos.computeX(screenWidth) : screenWidth / 2 + 128;
        int rwrCenterY = rwrPos != null ? rwrPos.computeY(screenHeight) : screenHeight - 180;
        float rwrScale = rwrPos != null ? rwrPos.scale : 0.8f;

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            List<RadarUnit> radarUnits = weaponUnit.getRadarUnits();
            if (!radarUnits.isEmpty()) {
                int radarCount = radarUnits.size();
                for (RadarUnit radarUnit : radarUnits) {
                    if (radarUnit.isUiHide() || !radarUnit.isOn()) {
                        continue;
                    }
                    // [RVP] 每个雷达独立位置：优先读 preset.radars[radarId]，没有则用整体 radar 位置
                    String radarId = radarUnit.getId();
                    UIPosition perPos = UIPresetManager.getRadarByPartId(presetName, radarId);
                    int rx = perPos != null ? perPos.computeX(screenWidth) : centerX;
                    int ry = perPos != null ? perPos.computeY(screenHeight) : centerY;
                    float perScale = perPos != null ? perPos.scale : radarScale;
                    float radius = radarCount == 1 ? 50.0f * perScale : 50.0f / radarCount / 0.6f * perScale;

                    poseStack.pushPose();
                    {
                        poseStack.translate(rx, ry, 0);

                        double maxScanDistance = radarUnit.getMaxScanDistance();
                        double yRotMin = radarUnit.getYRotMin();
                        double yRotMax = radarUnit.getYRotMax();
                        double yRotO = radarUnit.yRotO;
                        double yRot = radarUnit.getYRot();
                        int radarColor = Color.RADAR_SECTOR;
                        int lineColor = Color.GREEN;
                        Matrix4f matrix = poseStack.last().pose();
                        // 扫描扇区
                        if (yRotMax - yRotMin >= 360) {
                            yRotMin = 0;
                            yRotMax = 360;
                        } else {
                            drawRotatedText(guiGraphics, yRotMin + "°", -8, 0, radius + 8, (float) -yRotMin, Color.GREEN);
                            drawRotatedText(guiGraphics, yRotMax + "°", 8, 0, radius + 8, (float) -yRotMax, Color.GREEN);
                        }
                        drawRadarSector(matrix, 0, 0, radius, (float) yRotMin, (float) yRotMax, 32, radarColor);
                        drawRotatedText(guiGraphics, maxScanDistance + " m", 0, 0, radius + 8, 0, Color.GREEN);
                        // 扫描线
                        if (radarUnit.getLockedEntity() == null) {
                            float scanAngle = ywzj_rvp$computeScanAngle(radarUnit, partialTick, yRotO, yRot);
                            drawScanLine(matrix, 0, 0, radius, scanAngle, 0.4f, lineColor);
                        }
                        radarUnit.getDetectedEntities().values().forEach(detectedObject -> {
                            Vec3 v = detectedObject.detectedPosition.subtract(radarUnit.worldRadarPosition());
                            v = radarUnit.worldVecToLocalVec(v);
                            double l = v.length() / maxScanDistance * radius;
                            v = v.normalize().scale(-l);
                            // 目标
                            drawTarget(guiGraphics, v.x, v.z, 1, Color.GREEN, detectedObject, radarUnit);
                            // 锁定线
                            Entity lockedEntity = radarUnit.getLockedEntity();
                            if (lockedEntity != null && lockedEntity == detectedObject.entity) {
                                RenderHelper.drawLine(poseStack, v, 0.5f, Color.GREEN, 3, 1);
                            }
                        });
                        // [RVP] 头盔瞄准具 (HMD) 小扇形
                        ywzj_rvp$renderHmdSector(guiGraphics, radarUnit, matrix, radius);
                    }
                    poseStack.popPose(); // 恢复每个雷达独立的 translate
                }
            }
        }
        poseStack.popPose(); // 恢复最外层的 pushPose
        // RWR
        WarningReceiver warningReceiver = vehicle.warningReceiver;
        if (warningReceiver != null) {
            poseStack.pushPose();
            {
                // [RVP] RWR 居中告警文字在屏幕中心
                poseStack.pushPose();
                {
                    poseStack.translate((float) screenWidth / 2, (float) screenHeight / 2, 0);
                    if (warningReceiver.targets.values().stream().anyMatch(warnTarget -> warnTarget.warnType() == WarnType.MISSILE_LAUNCH)) {
                        guiGraphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("ui.missile_incoming"), 0, -55, Color.RED);
                    } else if (warningReceiver.targets.values().stream().anyMatch(warnTarget -> warnTarget.warnType() == WarnType.RADAR_LOCK)) {
                        guiGraphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("ui.being_locked"), 0, -55, Color.RED);
                    }
                }
                poseStack.popPose();
                // [RVP] RWR 圆圈位置 + 缩放
                poseStack.translate(rwrCenterX, rwrCenterY, 0);
                // RWR
                poseStack.pushPose();
                {
                    poseStack.scale(rwrScale, rwrScale, rwrScale);
                    guiGraphics.drawCenteredString(Minecraft.getInstance().font, "RWR", 0, -40, Color.GREEN);
                }
                poseStack.popPose();
                GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 3, Color.GREEN, 0.1f, 0, 0);
                GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 25, Color.GREEN, 0.01f, 0, 0);
                GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 20, Color.GREEN, 0.01f, 0, 0);
                GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 25, Color.BG_DARK_DIM, 1f, 0, 0);
                for (Map.Entry<Integer, WarningReceiver.WarnTarget> warnTargetEntry : warningReceiver.targets.entrySet()) {
                    Entity entity = LocalVehiclePlayer.instance.getPlayer().level().getEntity(warnTargetEntry.getKey());
                    WarningReceiver.WarnTarget warnTarget = warnTargetEntry.getValue();
                    if (entity == null) {
                        LocalVehiclePlayer.ServerEntity serverEntity = LocalVehiclePlayer.instance.serverEntities.get(warnTargetEntry.getKey());
                        if (serverEntity == null || serverEntity.entity == null) {
                            continue;
                        }
                        entity = serverEntity.entity;
                    }
                    double distance = entity.position().distanceTo(vehicle.position());
                    double scale = Math.min(1, distance / 512);
                    Vec3 direction = vehicle
                            .relativeRotDirection(entity.position().subtract(vehicle.position()), true)
                            .normalize()
                            .scale(scale * -25);
                    poseStack.pushPose();
                    {
                        if (warnTarget.warnType() ==  WarnType.RADAR_LOCK || warnTarget.warnType() == WarnType.MISSILE_LAUNCH) {
                            RenderHelper.drawLine(poseStack, direction, 0.8f, Color.GREEN, 3, 1);
                        }
                        direction = direction.normalize().scale(scale * 28);
                        poseStack.translate(direction.x, direction.z, 0);
                        poseStack.scale(0.6f, 0.6f, 0.6f);
                        String info = warnTargetEntry.getValue().info();
                        int color = Color.GREEN;
                        if (warnTarget.warnType() == WarnType.RADAR_SEARCH) {
                            float timeDiff = (float) (System.currentTimeMillis() - warnTarget.receivedTime());
                            float alpha = 1.0f - Math.min(1000f, timeDiff) / 1000f;
                            int alphaInt = Math.max(4, (int) (alpha * 255));
                            color = (alphaInt << 24) | (Color.GREEN & 0x00FFFFFF);
                        }
                        guiGraphics.drawCenteredString(Minecraft.getInstance().font, StringUtils.isEmpty(info) ? "?" : info, 0, 0, color);
                    }
                    poseStack.popPose();
                }
            }
            poseStack.popPose();
        }
    }

    public void drawTarget(GuiGraphics guiGraphics, double x, double y, int r, int color, RadarUnit.DetectedObject detectedObject, RadarUnit radarUnit) {
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            poseStack.translate(x - r, y - r, 0);
            guiGraphics.fill(0, 0, r * 2, r * 2, color);
            if (!(detectedObject.entity instanceof MissileEntity)) {
                guiGraphics.vLine(-2, -2, 3, color);
                guiGraphics.vLine(3, -2, 3, color);
                Team team = detectedObject.entity.getTeam();
                if (team != null && team.isAlliedTo(LocalVehiclePlayer.instance.getPlayer().getTeam())) {
                    guiGraphics.hLine(-2, 3, -3, color);
                }
            }
            poseStack.translate(1, 1, 0);
            Vec3 velocity = detectedObject.entity.getDeltaMovement();
            if (velocity.length() != 0) {
                Vec3 direction = velocity.normalize().scale(-5);
                direction = radarUnit.worldVecToLocalVec(direction);
                RenderHelper.drawLine(poseStack, direction, 0.8f, color, -1, -1);
            }
        }
        poseStack.popPose();
    }

    /**
     * [RVP] 计算扫描线角度，支持相控阵雷达动画和头盔瞄准具 (HMD)。
     * <p>
     * 优先级：HMD 模式（窄范围 ping-pong）> 相控阵模式 (scan_period_tick) > 普通 yRot lerp。
     * 仅当雷达数据标记了 {@code enable_hms: true} 时才启用 HMD 模式。
     * </p>
     */
    private float ywzj_rvp$computeScanAngle(RadarUnit radarUnit, float partialTick, double yRotO, double yRot) {
        // HMD 雷达模式：仅在支持 HMD 的雷达上生效
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        if (hmdState.isRadarHmd()) {
            RadarUnitData data = radarUnit.getData();
            if (data instanceof RadarUnitDataExt ext && ext.ywzj_rvp$isEnableHms()) {
                Vec3 aimDir = VectorUtil.rotToVec(hmdState.getSmoothPitch(), hmdState.getSmoothYaw()).normalize();
                Vec3 radarPos = radarUnit.worldRadarPosition();
                Vec3 radarToHead = radarPos.add(aimDir.scale(100)).subtract(radarPos);
                Vec2 localRot = radarUnit.worldVecToLocalRot(radarToHead);
                float yMin = radarUnit.getYRotMin();
                float yMax = radarUnit.getYRotMax();
                if (yMax - yMin >= 360f) {
                    yMin = 0f;
                    yMax = 360f;
                }
                float center = Mth.clamp((float) localRot.y, yMin, yMax);
                int tick = hmdState.getTickCount();
                float phase = ((tick % 5) + partialTick) / 5.0f;
                phase = Mth.clamp(phase, 0f, 1f);
                float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
                return center - 1.5f + 3.0f * pingPong;
            }
        }
        // 相控阵模式
        RadarUnitData data = radarUnit.getData();
        if (data instanceof RadarUnitDataExt ext
                && "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode())) {
            int periodTick = ext.ywzj_rvp$getScanPeriodTick();
            if (periodTick > 0) {
                float yRotMin = (float) radarUnit.getYRotMin();
                float yRotMax = (float) radarUnit.getYRotMax();
                float scanAz = yRotMax - yRotMin;
                if (scanAz >= 360f) {
                    yRotMin = 0f;
                    scanAz = 360f;
                }
                // [RVP] 首次开启雷达时从 0° 扫一次，再进入巡航
                int enabledTick = RadarEnabledTickHelper.getEnabledTick(radarUnit);
                int elapsed = radarUnit.getVehicle().tickCount - enabledTick;
                if (elapsed < periodTick) {
                    // 第一次扫描：单向从 yRotMin 扫到 yRotMax
                    float ratio = (elapsed + partialTick) / periodTick;
                    return yRotMin + scanAz * Mth.clamp(ratio, 0f, 1f);
                }
                // 巡航：ping-pong
                float phase = ((radarUnit.getVehicle().tickCount % periodTick) + partialTick) / periodTick;
                phase = Mth.clamp(phase, 0f, 1f);
                if (scanAz >= 360f) {
                    return phase * 360f;
                }
                float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
                return yRotMin + scanAz * pingPong;
            }
        }
        return (float) Mth.lerp(partialTick, yRotO, yRot);
    }

    /**
     * [RVP] 在雷达上渲染头盔瞄准具 (HMD) 小扇形。
     * 仅在 HMD 模式开启且当前雷达支持 HMD ({@code enable_hms: true}) 时绘制。
     * 扇形以 (0,0) 为圆心在雷达的本地坐标系中绘制（调用时已在雷达平移后）。
     */
    private void ywzj_rvp$renderHmdSector(GuiGraphics guiGraphics, RadarUnit radarUnit, Matrix4f matrix, float radius) {
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        if (!hmdState.isRadarHmd()) return;

        RadarUnitData data = radarUnit.getData();
        if (!(data instanceof RadarUnitDataExt ext) || !ext.ywzj_rvp$isEnableHms()) return;

        Vec3 aimDir = VectorUtil.rotToVec(hmdState.getSmoothPitch(), hmdState.getSmoothYaw()).normalize();
        Vec3 radarPos = radarUnit.worldRadarPosition();
        Vec3 radarToHead = radarPos.add(aimDir.scale(100)).subtract(radarPos);
        Vec2 localRot = radarUnit.worldVecToLocalRot(radarToHead);

        float yMin = radarUnit.getYRotMin();
        float yMax = radarUnit.getYRotMax();
        if (yMax - yMin >= 360f) {
            yMin = 0f;
            yMax = 360f;
        }
        float hmdBearing = Mth.clamp(-(float) localRot.y, -yMax, -yMin);

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

    private void drawRotatedText(GuiGraphics guiGraphics, String text, int cx, int cy, float r, float angleDeg, int color) {
        Font font = Minecraft.getInstance().font;
        float rad = -(float) Math.toRadians(angleDeg + 90);
        float x = cx + (float) Math.cos(rad) * r;
        float y = cy + (float) Math.sin(rad) * r;
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            poseStack.translate(x, y, 0);
            poseStack.scale(0.8f, 0.8f, 0.8f);
            guiGraphics.drawCenteredString(font, text, 0, 0, color);
        }
        poseStack.popPose();
    }

    private void drawRadarSector(Matrix4f matrix, int cx, int cy, float r, float startDeg, float endDeg, int segments, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferbuilder = tesselator.getBuilder();

        bufferbuilder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        float a = (float)(color >> 24 & 255) / 255.0F;
        float r_col = (float)(color >> 16 & 255) / 255.0F;
        float g = (float)(color >> 8 & 255) / 255.0F;
        float b = (float)(color & 255) / 255.0F;

        bufferbuilder.vertex(matrix, (float)cx, (float)cy, 0).color(r_col, g, b, a).endVertex();

        for (int i = 0; i <= segments; i++) {
            float angle = startDeg + (endDeg - startDeg) * ((float)i / segments);
            float rad = -(float) Math.toRadians(angle + 90);
            float x = cx + (float)Math.cos(rad) * r;
            float y = cy + (float)Math.sin(rad) * r;
            bufferbuilder.vertex(matrix, x, y, 0).color(r_col, g, b, a).endVertex();
        }

        tesselator.end();
        RenderSystem.disableBlend();
    }

    private void drawScanLine(Matrix4f matrix, int cx, int cy, float r, float angleDeg, float thickness, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferbuilder = tesselator.getBuilder();

        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float a = (float)(color >> 24 & 255) / 255.0F;
        float r_col = (float)(color >> 16 & 255) / 255.0F;
        float g = (float)(color >> 8 & 255) / 255.0F;
        float b = (float)(color & 255) / 255.0F;

        float rad = (float) Math.toRadians(angleDeg - 90);

        float dirX = (float) Math.cos(rad);
        float dirY = (float) Math.sin(rad);

        float perpX = (float) -Math.sin(rad);
        float perpY = (float) Math.cos(rad);

        float halfWidth = thickness / 2.0f;

        bufferbuilder.vertex(matrix, cx - perpX * halfWidth, cy - perpY * halfWidth, 0).color(r_col, g, b, a).endVertex();
        bufferbuilder.vertex(matrix, cx + perpX * halfWidth, cy + perpY * halfWidth, 0).color(r_col, g, b, a).endVertex();
        bufferbuilder.vertex(matrix, cx + dirX * r + perpX * halfWidth, cy + dirY * r + perpY * halfWidth, 0).color(r_col, g, b, a).endVertex();
        bufferbuilder.vertex(matrix, cx + dirX * r - perpX * halfWidth, cy + dirY * r - perpY * halfWidth, 0).color(r_col, g, b, a).endVertex();

        tesselator.end();
        RenderSystem.disableBlend();
    }
}
