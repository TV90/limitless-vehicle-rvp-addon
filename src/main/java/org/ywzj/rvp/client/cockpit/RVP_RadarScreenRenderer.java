package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;

/**
 * 雷达屏内容渲染器（2026-10-14 座舱屏幕批，2026-10-14b 回退至 20:32 基线）。
 *
 * <p>V1 简化版：量程圈+旋转扫描线+探测符号（方位随本车航向旋转、距离线性映射）。
 * 探测满程按 512 格（半径线性映射）。圆盘底=不透明熄屏黑（2026-10-14 修复：
 * BG_DARK_DIM 为半透明罩，屏骨直采时透出底下机体骨，已弃用）。</p>
 */
public final class RVP_RadarScreenRenderer {

    /** 探测满程（格）：超出该距离的探测点画在外圈上。 */
    private static final double RANGE_BLOCKS = 512.0;

    private RVP_RadarScreenRenderer() {}

    /** 渲染雷达罗盘帧。 */
    public static void render(GuiGraphics graphics, int width, int height, AbstractVehicle vehicle) {
        // 熄屏底色（不透明深灰近黑，transparent 渲染下 alpha=1 区域为实心）
        graphics.fill(0, 0, width, height, 0xFF0A0C0E);
        int cx = width / 2;
        int cy = height / 2;
        // UV 占比对齐 MCHR（RADAR_UV_DIAMETER_RATIO=0.70）：圆盘直径=短边×0.70，四周留 15% 防出血。
        int outer = (int) (Math.min(width, height) * 0.35f);
        var pose = graphics.pose();
        long gameTime = vehicle.level().getGameTime();

        WeaponUnit weaponUnit = RVP_CockpitScreenManager.currentWeaponUnit();
        RadarUnit radar = weaponUnit == null ? null : weaponUnit.getMainRadarUnit();
        if (radar == null) {
            graphics.drawCenteredString(font(), "NO RADAR", cx, cy, Color.GREEN);
            return;
        }

        // 量程圈组 + 中心点 + 扫描线（匀速旋转，60 tick 一圈）。
        // 圆盘不透明底必须先画（在后会盖掉环/文字）；圈用 Canvas 自绘
        // （position_color 立即模式，绕开 HUD_CIRCLE shader 的 RT alpha 失效）。
        RVP_CockpitCanvas.drawDiscImmediate(pose, cx, cy, outer, 0xFF0A0C0E);
        RVP_CockpitCanvas.drawDiscImmediate(pose, cx, cy, 3, Color.GREEN);
        RVP_CockpitCanvas.drawRingImmediate(pose, cx, cy, outer * 0.5f + 2, outer * 0.5f - 2, Color.GREEN);
        RVP_CockpitCanvas.drawRingImmediate(pose, cx, cy, outer * 0.98f + 2, outer * 0.98f - 2, Color.GREEN);
        double scanAngle = (gameTime % 60) / 60.0 * Math.PI * 2.0;
        Vec3 scanDir = new Vec3(Math.sin(scanAngle), 0, -Math.cos(scanAngle)).scale(outer * 0.95f);
        RVP_CockpitCanvas.drawLineImmediate(pose, cx, cy,
                cx + (float) scanDir.x, cy + (float) scanDir.z, 2.0F, Color.GREEN);

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
            double distance = worldPos.distanceTo(vehicle.position());
            double scale = Math.min(1, distance / RANGE_BLOCKS);
            // 调用本体相对方位解算：车体系方向（车头朝上），与 HUD 雷达同语义。
            Vec3 direction = vehicle
                    .relativeRotDirection(worldPos.subtract(vehicle.position()), true)
                    .normalize()
                    .scale(scale * outer * 0.95f);
            int color = isFriendly(entity, vehicle) ? 0xFF3D9140 : Color.GREEN;
            int r = 3;
            pose.pushPose();
            {
                graphics.fill(cx + (int) direction.x - r, cy + (int) direction.z - r,
                        cx + (int) direction.x + r, cy + (int) direction.z + r, color);
                // 速度矢量（搬 drawTarget：速度方向画短线，提示目标动向）
                if (entity != null) {
                    Vec3 velocity = entity.getDeltaMovement();
                    if (velocity.lengthSqr() > 1.0E-6) {
                        Vec3 vDir = vehicle
                                .relativeRotDirection(velocity.normalize().scale(-8), false)
                                .normalize()
                                .scale(8);
                        RVP_CockpitCanvas.drawLineImmediate(pose,
                                cx + (float) direction.x, cy + (float) direction.z,
                                cx + (float) direction.x + (float) vDir.x, cy + (float) direction.z + (float) vDir.z,
                                2.0F, color);
                    }
                }
            }
            pose.popPose();
        }
        // 量程文字（屏骨字体放大）
        RVP_CockpitCanvas.drawScaledCenteredString(graphics, pose, "512m", cx, cy + outer - 10, Color.GREEN);
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

    /** 延迟取 MC 字体的小封装（避免静态初始化时序问题）。 */
    private static net.minecraft.client.gui.Font font() {
        return net.minecraft.client.Minecraft.getInstance().font;
    }
}
