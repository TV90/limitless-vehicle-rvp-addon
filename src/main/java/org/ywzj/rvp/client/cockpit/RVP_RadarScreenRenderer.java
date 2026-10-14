package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.gui.RVP_RadarOverlay;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;

/**
 * 雷达屏内容渲染器（2026-10-14 座舱屏幕批；2026-10-15 扫描动画与 HUD 雷达同源化）。
 *
 * <p>量程圈+扫描扇区+扫描线+探测符号（方位随本车航向旋转、距离线性映射）。
 * 扫描线角度与 RVP 雷达 HUD 共用 {@link RVP_RadarOverlay#computeScanAngle}——
 * 相控阵（scan_animation_mode=phase + scan_period_tick，开机先单向扫一圈再 ping-pong 巡航）、
 * HMD、机械 yRot 三分支同源，锁定目标时不画扫描线（均与 HUD 行为一致）。
 * 量程取雷达实际 maxScanDistance（不再写死 512 格）。圆盘底=不透明熄屏黑。</p>
 */
public final class RVP_RadarScreenRenderer {

    private RVP_RadarScreenRenderer() {}

    /** 渲染雷达罗盘帧。 */
    public static void render(GuiGraphics graphics, int width, int height, AbstractVehicle vehicle) {
        var pose = graphics.pose();
        // 熄屏底色（不透明深灰近黑，transparent 渲染下 alpha=1 区域为实心）。
        // 必须走立即模式（2026-10-14 深夜修）：GuiGraphics.fill 攒批内容要到 painter 返回后
        // 的 endBatch 才落屏，而本渲染器首次字体请求在末尾量程文字——若底色走攒批，会在立即
        // 圆盘/环/扫描线之后才刷下，整屏盖掉它们（症状=屏上只剩深底+探测方块+量程文字）。
        // RWR 同隐患是靠顶部告警文字先画触发提前 endBatch 才躲过的，勿效仿。
        RVP_CockpitCanvas.fillRectImmediate(pose, 0, 0, width, height, 0xFF0A0C0E);
        int cx = width / 2;
        int cy = height / 2;
        // UV 占比对齐 MCHR（RADAR_UV_DIAMETER_RATIO=0.70）：圆盘直径=短边×0.70，四周留 15% 防出血。
        int outer = (int) (Math.min(width, height) * 0.35f);

        // 雷达源：从"屏内容载具"自己身上取（玩家所坐部件 → 载具部件表），不再读本体席位——
        // 本体状态被上游清空时席位变 null，这块屏会整块只剩 NO RADAR（2026-10-15 定案）。
        RadarUnit radar = RVP_CockpitScreenManager.mainRadarUnit(vehicle);
        if (radar == null) {
            // 无雷达提示与其它屏内文字同款走放大版（GuiGraphics 文字攒批，endBatch 时在
            // 全部立即图元之上，层序正确）。
            RVP_CockpitCanvas.drawScaledCenteredString(graphics, pose, "NO RADAR", cx, cy, Color.GREEN);
            return;
        }

        // 量程与扫描范围与 HUD 雷达同源（maxScanDistance / yRotMin~yRotMax），不再写死 512 格
        // （2026-10-15 用户定案：屏上雷达必须与雷达 UI 关联，此前是独立装饰动画）。
        double range = radar.getMaxScanDistance();
        float yMin = (float) radar.getYRotMin();
        float yMax = (float) radar.getYRotMax();
        boolean fullCircle = yMin == 0f && (yMax - yMin) >= 360f;

        // 量程圈组 + 中心点 + 扫描线。
        // 圆盘不透明底必须先画（在后会盖掉环/文字）；圈用 Canvas 自绘
        // （position_color 立即模式，绕开 HUD_CIRCLE shader 的 RT alpha 失效）。
        RVP_CockpitCanvas.drawDiscImmediate(pose, cx, cy, outer, 0xFF0A0C0E);
        // 扫描扇区：仅非全向雷达画（全向=满圆盘底，与 HUD 语义一致）。色用 HUD RADAR_SECTOR
        // (0x4433FF33) 在黑底上的预混不透明等效色——RT 立即直写不走混合，低 alpha 会变纯色块。
        if (!fullCircle) {
            RVP_CockpitCanvas.drawSectorImmediate(pose, cx, cy, outer * 0.95f, yMin, yMax, 0xFF154D18);
        }
        RVP_CockpitCanvas.drawDiscImmediate(pose, cx, cy, 3, Color.GREEN);
        RVP_CockpitCanvas.drawRingImmediate(pose, cx, cy, outer * 0.5f + 2, outer * 0.5f - 2, Color.GREEN);
        RVP_CockpitCanvas.drawRingImmediate(pose, cx, cy, outer * 0.98f + 2, outer * 0.98f - 2, Color.GREEN);
        // 扫描线：与 HUD 雷达共用同一计算（RVP_RadarOverlay.computeScanAngle——
        // 相控阵 scan_period_tick / HMD / 机械 yRot 三分支同源），锁定目标时不画（同 HUD）。
        // partialTick 传 0：RT 内容每 ClientTick 刷新一次（20Hz），tick 内插值无意义。
        if (radar.getLockedEntity() == null) {
            float scanDeg = RVP_RadarOverlay.computeScanAngle(radar, 0f, radar.yRotO, radar.getYRot());
            double scanRad = Math.toRadians(scanDeg - 90);
            RVP_CockpitCanvas.drawLineImmediate(pose, cx, cy,
                    cx + (float) Math.cos(scanRad) * outer * 0.95f,
                    cy + (float) Math.sin(scanRad) * outer * 0.95f, 2.0F, Color.GREEN);
        }

        // 探测符号：方位随本车航向旋转、距离线性映射 0~512 格
        HashMap<Integer, RadarUnit.DetectedObject> detected = radar.getDetectedEntities();
        if (detected == null) {
            return;
        }
        for (RadarUnit.DetectedObject detectedObject : detected.values()) {
            Entity entity = detectedObject.entity;
            Vec3 worldPos = detectedObject.detectedPosition != null
                    ? detectedObject.detectedPosition
                    : (entity != null ? entity.position() : null);
            if (worldPos == null) {
                continue;
            }
            // 目标换算与 HUD 雷达逐字同款（VehicleRadarOverlay:81-87）：
            // 世界差矢量（相对雷达塔位）→ 雷达本体旋转共轭 → **取负** → 按量程线性映射。
            // 此前用 vehicle.relativeRotDirection 且不带负号 ⇒ 与 HUD 差 180°（2026-10-15 实测：
            // 飞机往右上飞、屏上往左下）。雷达共轭用 radarUnit.worldVecToLocalVec（雷达塔自身
            // 旋转），不用载具旋转——雷达塔随航向转动时两者才有差异。
            Vec3 local = radar.worldVecToLocalVec(worldPos.subtract(radar.worldRadarPosition()));
            double l = Math.min(1.0, local.length() / range) * (outer * 0.95f);
            Vec3 direction = local.lengthSqr() > 1.0E-6
                    ? local.normalize().scale(-l) : new Vec3(0, 0, 0);
            int color = isFriendly(entity, vehicle) ? 0xFF3D9140 : Color.GREEN;
            int r = 5;
            pose.pushPose();
            {
                // 探测方块同样走立即填充（防攒批/立即交错：攒批版会拖到 endBatch 才落屏，
                // 层序与代码提交序脱钩——与底色同批次晚刷会盖掉先画的环/扫描线）。
                float sx = cx + (float) direction.x;
                float sy = cy + (float) direction.z;
                RVP_CockpitCanvas.fillRectImmediate(pose, sx - r, sy - r, sx + r, sy + r, color);
                // 速度矢量：HUD drawTarget 同款数学（velocity 取负 → 雷达共轭变换），
                // 方向必与 HUD 一致；长度按屏比例加大（HUD 5×0.8≈4px@半径50 ⇒ 屏上 18px），宽 3。
                if (entity != null) {
                    Vec3 velocity = entity.getDeltaMovement();
                    if (velocity.lengthSqr() > 1.0E-6) {
                        Vec3 vDir = radar.worldVecToLocalVec(velocity.normalize().scale(-5))
                                .normalize().scale(18);
                        RVP_CockpitCanvas.drawLineImmediate(pose,
                                sx, sy,
                                sx + (float) vDir.x, sy + (float) vDir.z,
                                3.0F, color);
                    }
                }
            }
            pose.popPose();
        }
        // 量程文字（屏骨字体放大）：与 HUD 同源 maxScanDistance
        RVP_CockpitCanvas.drawScaledCenteredString(graphics, pose, range + " m", cx, cy + outer - 10, Color.GREEN);
    }

    /** 简易 IFF：同队为友军（绿色暗色调），其余按敌对亮绿。 */
    private static boolean isFriendly(Entity entity, AbstractVehicle observer) {
        var team = observer.getTeam();
        if (team == null || entity == null) {
            return false;
        }
        Entity driver = entity instanceof AbstractVehicle vehicle ? vehicle.getDriver() : entity;
        return driver != null && team.isAlliedTo(driver.getTeam());
    }
}
