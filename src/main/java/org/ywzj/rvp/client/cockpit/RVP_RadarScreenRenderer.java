package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;

/**
 * 雷达屏内容渲染器（2026-10-14 座舱屏幕批）：探测罗盘绘制到 512² 屏画布。
 *
 * <p>V1 简化版：量程圈+旋转扫描线+探测符号（方块+速度矢量，搬自
 * {@code RVP_RadarOverlay.drawTarget}）。扫描线用游戏时间匀速旋转，HUD 雷达的
 * HMD/相控阵/机械三态相位逻辑后续迭代接入。探测满程按 512 格（半径线性映射）。</p>
 */
public final class RVP_RadarScreenRenderer {

    /** 探测满程（格）：超出该距离的探测点画在外圈上。 */
    private static final double RANGE_BLOCKS = 512.0;

    private RVP_RadarScreenRenderer() {}

    /** 渲染雷达罗盘帧。 */
    public static void render(GuiGraphics graphics, int width, int height, AbstractVehicle vehicle) {
        graphics.fill(0, 0, width, height, 0xFF0A0C0E);
        int cx = width / 2;
        int cy = height / 2;
        int outer = (int) (Math.min(width, height) * 0.45f);
        var pose = graphics.pose();
        long gameTime = vehicle.level().getGameTime();

        WeaponUnit weaponUnit = RVP_CockpitScreenManager.currentWeaponUnit();
        RadarUnit radar = weaponUnit == null ? null : weaponUnit.getMainRadarUnit();
        if (radar == null) {
            graphics.drawCenteredString(font(), "NO RADAR", cx, cy, Color.GREEN);
            return;
        }

        // 量程圈组 + 中心点 + 扫描线（匀速旋转，60 tick 一圈）
        GuiHelper.drawCircle(pose, cx, cy, 3, Color.GREEN, 0.1f, 0, 0);
        GuiHelper.drawCircle(pose, cx, cy, outer * 0.5f, Color.GREEN, 0.01f, 0, 0);
        GuiHelper.drawCircle(pose, cx, cy, outer * 0.98f, Color.GREEN, 0.01f, 0, 0);
        GuiHelper.drawCircle(pose, cx, cy, outer, Color.BG_DARK_DIM, 1f, 0, 0);
        double scanAngle = (gameTime % 60) / 60.0 * Math.PI * 2.0;
        Vec3 scanDir = new Vec3(Math.sin(scanAngle), 0, -Math.cos(scanAngle)).scale(outer * 0.98f);
        pose.pushPose();
        RenderHelper.drawLine(pose, scanDir, 0.8f, Color.GREEN, 2, 1);
        pose.popPose();

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
                    .scale(scale * outer * 0.98f);
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
                        pose.translate(cx + direction.x, cy + direction.z, 0);
                        RenderHelper.drawLine(pose, vDir, 0.8f, color, -1, -1);
                    }
                }
            }
            pose.popPose();
        }
        // 量程文字
        graphics.drawCenteredString(font(), "512m", cx, cy + outer - 10, Color.GREEN);
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
