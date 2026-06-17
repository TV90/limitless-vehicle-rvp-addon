package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public class RVP_HmdOverlay {

    private static final int COLOR_GREEN = Color.GREEN;
    private static final int COLOR_RED = 0xFFFF2B2B;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final float RADAR_HMD_HALF_FOV_DEG = 1.5f;
    private static final float GROUND_IR_HALF_FOV_DEG = 2.4f;
    private static final int CROSS_HALF = 8;

    private RVP_HmdOverlay() {}

    public static void render(GuiGraphics guiGraphics) {
        RVP_ClientHmdState state = RVP_ClientHmdState.getInstance();
        if (!state.isHmdMode()) return;
        Minecraft mc = Minecraft.getInstance();
        if (state.isRadarHmd()) {
            renderRadarHmd(guiGraphics, mc, state);
        } else if (state.isGroundIr()) {
            renderGroundIr(guiGraphics, mc, state);
        } else {
            renderAirIr(guiGraphics, mc, state);
        }
    }

    // ==================== RADAR HMD ====================

    private static void renderRadarHmd(GuiGraphics guiGraphics, Minecraft mc, RVP_ClientHmdState state) {
        boolean warning = state.isWarning();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 hit = projectToScreen(mc, camera, state.getSmoothPitch(), state.getSmoothYaw());
        if (hit == null) return;
        int cx = (int) hit.x, cy = (int) hit.y;
        double fov = mc.options.fov().get();
        double tan = Math.tan(Math.toRadians(fov / 2.0));
        if (tan <= 0) return;
        int boxHalf = (int) (Math.tan(Math.toRadians(RADAR_HMD_HALF_FOV_DEG)) / tan * cy * 1.1);
        if (boxHalf < 8) boxHalf = 8;
        int left = cx - boxHalf, right = cx + boxHalf, top = cy - boxHalf, bottom = cy + boxHalf;
        boolean visible = (System.currentTimeMillis() % 700) < 350;
        int base = warning ? COLOR_RED : COLOR_GREEN;
        int color = visible ? base : (base & 0x00FFFFFF) | 0x02000000;
        drawCornerBox(guiGraphics, left, right, top, bottom, color);
    }

    // ==================== IR HMD（对地）====================

    private static void renderGroundIr(GuiGraphics guiGraphics, Minecraft mc, RVP_ClientHmdState state) {
        double tan = getTanFov(mc);
        if (tan <= 0) return;
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) return;
        Entity locked = weaponUnit.getLockedEntity();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();

        boolean isScope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;

        Vec2 weaponRot = weaponUnit.worldRot();
        Vec3 refDir = VectorUtil.rotToVec(weaponRot.x, weaponRot.y).normalize();
        float guideHeadAngle = state.getIrGuideHeadMaxAngle();

        // 无大圈，仅计算离轴角
        float hmdPitch = state.getSmoothPitch(), hmdYaw = state.getSmoothYaw();
        Vec3 hmdDir = VectorUtil.rotToVec(hmdPitch, hmdYaw).normalize();
        double hmdAngle = Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, refDir.dot(hmdDir)))));
        boolean outOfLimit = hmdAngle > guideHeadAngle;

        // 角度钳制
        Vec3 finalDir = hmdDir;
        if (outOfLimit && hmdAngle > 0.1) {
            Vec2 refRot = VectorUtil.vecToRot(refDir);
            float dp = hmdPitch - refRot.x, dy = hmdYaw - refRot.y;
            double dist = Math.sqrt(dp * dp + dy * dy);
            if (dist > 0.01) {
                double scale = guideHeadAngle / dist;
                finalDir = VectorUtil.rotToVec(
                        refRot.x + dp * (float) scale,
                        refRot.y + dy * (float) scale).normalize();
            }
        }

        // 超出离轴角时红色闪烁，否则白色
        boolean visible = (System.currentTimeMillis() % 700) < 350;
        int frameColor;
        if (outOfLimit) {
            frameColor = visible ? COLOR_RED : (COLOR_RED & 0x00FFFFFF) | 0x02000000;
        } else {
            frameColor = visible ? COLOR_WHITE : (COLOR_WHITE & 0x00FFFFFF) | 0x02000000;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (locked != null && locked.isAlive()) {
            // 锁定 → 框+十字在目标上
            Vec3 screen = VectorUtil.worldToScreen(locked.getBoundingBox().getCenter());
            if (screen.z > 0) {
                int tx = (int) screen.x, ty = (int) screen.y;
                int boxHalf = calcBoxHalf(mc, tan, locked);
                drawCornerBox(guiGraphics, tx - boxHalf, tx + boxHalf, ty - boxHalf, ty + boxHalf, frameColor);
                guiGraphics.fill(tx - CROSS_HALF, ty, tx + CROSS_HALF + 1, ty + 1, frameColor);
                guiGraphics.fill(tx, ty - CROSS_HALF, tx + 1, ty + CROSS_HALF + 1, frameColor);
            }
        } else {
            // 未锁定：观瞄在屏幕中心，非观瞄跟随 HMD 方向（钳制后）
            int hx, hy;
            if (isScope) {
                hx = mc.getWindow().getGuiScaledWidth() / 2;
                hy = mc.getWindow().getGuiScaledHeight() / 2;
            } else {
                Vec3 finalPos = projectWorldPos(mc, camPos, finalDir);
                if (finalPos == null) { RenderSystem.disableBlend(); return; }
                hx = (int) finalPos.x;
                hy = (int) finalPos.y;
            }

            int boxHalf = (int) (Math.tan(Math.toRadians(GROUND_IR_HALF_FOV_DEG)) / tan * hy * 1.1);
            if (boxHalf < 10) boxHalf = 10;
            int color = outOfLimit
                    ? (visible ? COLOR_RED : (COLOR_RED & 0x00FFFFFF) | 0x02000000)
                    : (visible ? COLOR_WHITE : (COLOR_WHITE & 0x00FFFFFF) | 0x02000000);
            drawCornerBox(guiGraphics, hx - boxHalf, hx + boxHalf, hy - boxHalf, hy + boxHalf, color);
        }
        RenderSystem.disableBlend();
    }

    // ==================== IR HMD（对空）====================

    private static void renderAirIr(GuiGraphics guiGraphics, Minecraft mc, RVP_ClientHmdState state) {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || weaponUnit.getLockedEntity() != null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec2 weaponRot = weaponUnit.worldRot();
        Vec3 weaponDir = VectorUtil.rotToVec(weaponRot.x, weaponRot.y).normalize();

        float hmdPitch = state.getSmoothPitch(), hmdYaw = state.getSmoothYaw();
        Vec3 hmdDir = VectorUtil.rotToVec(hmdPitch, hmdYaw).normalize();

        float limit = state.getIrGuideHeadMaxAngle();
        double dot = Math.max(-1.0, Math.min(1.0, weaponDir.dot(hmdDir)));
        double angleDeg = Math.toDegrees(Math.acos(dot));
        if (angleDeg > limit && angleDeg > 0.1) {
            float dp = hmdPitch - weaponRot.x, dy = hmdYaw - weaponRot.y;
            double d = Math.sqrt(dp * dp + dy * dy);
            if (d > 0.01) {
                double s = limit / d;
                hmdPitch = weaponRot.x + dp * (float) s;
                hmdYaw   = weaponRot.y + dy * (float) s;
            }
        }

        Vec3 pos = projectWorldPos(mc, camPos, VectorUtil.rotToVec(hmdPitch, hmdYaw).normalize());
        if (pos == null) return;
        int hx = (int) pos.x, hy = (int) pos.y;
        boolean visible = (System.currentTimeMillis() % 700) < 350;
        int color = visible ? COLOR_WHITE : (COLOR_WHITE & 0x00FFFFFF) | 0x02000000;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        GuiHelper.drawArc(guiGraphics, hx, hy, 15, 0.5f, 0f, 1f, color);
        RenderSystem.disableBlend();
    }

    // ==================== 通用 ====================

    private static void drawCornerBox(GuiGraphics gg, int left, int right, int top, int bottom, int color) {
        int cl = Math.max(4, (right - left) / 4);
        gg.fill(left, top, left + cl, top + 1, color);
        gg.fill(left, top, left + 1, top + cl, color);
        gg.fill(right - cl, top, right, top + 1, color);
        gg.fill(right - 1, top, right, top + cl, color);
        gg.fill(left, bottom - 1, left + cl, bottom, color);
        gg.fill(left, bottom - cl, left + 1, bottom, color);
        gg.fill(right - cl, bottom - 1, right, bottom, color);
        gg.fill(right - 1, bottom - cl, right, bottom, color);
    }

    /** 根据目标和视角计算框半宽（×1.2）。 */
    private static int calcBoxHalf(Minecraft mc, double tan, Entity target) {
        Vec3 s = VectorUtil.worldToScreen(target.getBoundingBox().getCenter());
        if (s.z <= 0) return 10;
        int cy = (int) s.y;
        int boxHalf = (int) (Math.tan(Math.toRadians(GROUND_IR_HALF_FOV_DEG)) / tan * cy * 1.1);
        return Math.max(10, boxHalf);
    }

    private static double getTanFov(Minecraft mc) {
        return Math.tan(Math.toRadians(mc.options.fov().get() / 2.0));
    }

    private static Vec3 projectWorldPos(Minecraft mc, Vec3 origin, Vec3 dir) {
        Vec3 end = origin.add(dir.scale(128));
        Vec3 hit = VectorUtil.hitPosition(mc.player, origin, end);
        Vec3 screen = VectorUtil.worldToScreen(hit);
        return screen.z > 0 ? screen : null;
    }

    private static Vec3 projectToScreen(Minecraft mc, Camera camera, float pitch, float yaw) {
        return projectWorldPos(mc, camera.getPosition(),
                VectorUtil.rotToVec(pitch, yaw).normalize());
    }
}
