package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.mixin.accessor.BaseBulletAccessor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.List;

/**
 * MSL 指示器叠加层。
 * 对玩家自己的 RVP 导弹渲染红色菱形框 + MSL + 距离，
 * 对他人导弹只在燃烧动力段内渲染。
 */
@Mod.EventBusSubscriber(value = net.minecraftforge.api.distmarker.Dist.CLIENT, modid = RVP_MOD.MOD_ID)
public class RVP_MslOverlay {

    private static final int DIAMOND_SIZE = 10;
    private static final int LINE_COLOR = 0xFFCC0000; // 红色

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.player.getVehicle() instanceof AbstractVehicle playerVehicle)) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // 获取世界中的所有 RVP 导弹（使用较大搜索半径覆盖全射程）
        List<? extends RVP_BaseBullet> missiles = mc.level.getEntitiesOfClass(
                RVP_BaseBullet.class,
                mc.player.getBoundingBox().inflate(8192));

        for (RVP_BaseBullet missile : missiles) {
            if (missile.isRemoved() || !missile.isAlive()) continue;

            // 仅显示配置了 show_msl_indicator=true 的导弹
            BaseBulletAccessor acc = (BaseBulletAccessor) missile;
            if (!acc.isShowMslIndicator()) continue;

            AbstractVehicle shooterVehicle = missile.getShooterVehicle();
            boolean isOwn = (shooterVehicle == playerVehicle);

            // 所有人（包括自己）的导弹都只在燃烧动力段内渲染
            int burnEnd = acc.getMotorBurnEndTick();
            if (burnEnd <= 0 || missile.tickCount > burnEnd) continue;

            // 投影到屏幕
            Vec3 targetPos = missile.position();
            Vec3 screenPos = VectorUtil.worldToScreen(targetPos);
            if (screenPos == null || screenPos.z < 0) continue;

            double dist = mc.player.position().distanceTo(targetPos);
            String distStr = String.format("%.0fm", dist);

            int screenX = (int) screenPos.x;
            int screenY = (int) screenPos.y;

            // 忽略屏幕边缘外的目标（留 20px 边距）
            if (screenX < -20 || screenX > screenWidth + 20
                    || screenY < -20 || screenY > screenHeight + 20) continue;

            // 渲染红色菱形框
            drawDiamond(guiGraphics, screenX, screenY, DIAMOND_SIZE);

            // 菱形框下方显示 MSL 和距离（两行，居中对齐）
            int textX = screenX;
            String mslText = "MSL  " + distStr;
            int textWidth = mc.font.width(mslText);
            guiGraphics.drawString(mc.font, mslText, textX - textWidth / 2, screenY + DIAMOND_SIZE + 2, LINE_COLOR);
        }
    }

    /**
     * 绘制红色空心菱形框（3px 粗线）。
     */
    private static void drawDiamond(GuiGraphics guiGraphics, int cx, int cy, int size) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        GL11.glLineWidth(5.0f);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        float r = ((LINE_COLOR >> 16) & 0xFF) / 255.0f;
        float g = ((LINE_COLOR >> 8) & 0xFF) / 255.0f;
        float b = (LINE_COLOR & 0xFF) / 255.0f;
        float a = ((LINE_COLOR >> 24) & 0xFF) / 255.0f;

        // 菱形四点闭合：上→右→下→左→上
        buf.vertex(matrix, cx, cy - size, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, cx + size, cy, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, cx, cy + size, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, cx - size, cy, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, cx, cy - size, 0).color(r, g, b, a).endVertex();

        tess.end();
        GL11.glLineWidth(1.0f);
        RenderSystem.disableBlend();
    }
}
