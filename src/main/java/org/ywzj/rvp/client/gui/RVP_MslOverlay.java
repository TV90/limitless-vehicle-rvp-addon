package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.cockpit.RVP_CockpitDiag;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.mixin.accessor.BaseBulletAccessor;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
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
    /**
     * 菱形描边厚度（像素，等于几何带宽）。
     *
     * <p>为什么是 1.0 而不是 5.0：旧实现写的 {@code GL11.glLineWidth(5.0f)} 在核心
     * （forward-compatible）上下文里**非法**——NVIDIA 驱动直接忽略、线宽退回默认 1.0
     * （就是那批 GL_INVALID_VALUE 报错的来源）。也就是说旧观感的实际宽度一直是 1 px；
     * 换成真实四边形描边后若沿用 5.0 就会**真的画出 5 px**，看起来粗了 5 倍
     * （2026-10-15 用户实测反馈）。想调粗细改这一个常量即可。</p>
     */
    private static final float DIAMOND_THICKNESS = 1.0f;
    private static final int LINE_COLOR = 0xFFCC0000; // 红色
    /** 自己的导弹动力段烧完后，最远渲染距离（格）。 */
    private static final double OWN_MAX_RENDER_DIST = 4096.0;

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        // 目的：RenderGuiOverlayEvent 对每个已渲染 overlay 各触发一次（每帧 40+ 次），
        // 而本处理器的全实体遍历 + 逐导弹解析只应每帧执行一次——锚定每帧必渲染的
        // 原生 CHAT_PANEL 层，其余事件全部忽略（修复：多弹在飞时帧率腰斩）
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.player.getVehicle() instanceof AbstractVehicle playerVehicle)) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // 获取世界中的所有 RVP 导弹（覆盖全射程；O(实体) 遍历，替代 ±8192 立方体 getEntitiesOfClass，
        // 客户端每帧渲染路径，16384³ 的 section 索引遍历灾难级）
        List<RVP_BaseBullet> missiles = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof RVP_BaseBullet bullet
                    && bullet.position().distanceToSqr(mc.player.position()) <= 8192.0 * 8192.0) {
                missiles.add(bullet);
            }
        }

        // 找出自己发射且动力段已烧完的导弹中最晚发射的那一枚
        RVP_BaseBullet latestBurnedOutOwn = null;
        for (RVP_BaseBullet missile : missiles) {
            if (missile.isRemoved() || !missile.isAlive()) continue;
            BaseBulletAccessor acc = (BaseBulletAccessor) missile;
            if (!acc.isShowMslIndicator()) continue;

            AbstractVehicle shooterVehicle = missile.getShooterVehicle();
            boolean isOwn = (shooterVehicle == playerVehicle);
            if (!isOwn) continue;
            if (missile.isMotorBurningNow()) continue; // 只看烧完的

            if (latestBurnedOutOwn == null || missile.tickCount > latestBurnedOutOwn.tickCount) {
                latestBurnedOutOwn = missile;
            }
        }

        for (RVP_BaseBullet missile : missiles) {
            if (missile.isRemoved() || !missile.isAlive()) continue;

            // 仅显示配置了 show_msl_indicator=true 的导弹
            BaseBulletAccessor acc = (BaseBulletAccessor) missile;
            if (!acc.isShowMslIndicator()) continue;

            AbstractVehicle shooterVehicle = missile.getShooterVehicle();
            boolean isOwn = (shooterVehicle == playerVehicle);

            if (isOwn) {
                // 自己的导弹：动力段燃烧时始终渲染
                // 动力段烧完：只渲染最晚发射的那一枚
                if (!missile.isMotorBurningNow()) {
                    if (missile != latestBurnedOutOwn) continue;
                    // 烧完后最远渲染距离限制
                    double dist = mc.player.position().distanceTo(missile.position());
                    if (dist > OWN_MAX_RENDER_DIST) continue;
                }
            } else {
                // 他人的导弹：只在燃烧动力段内渲染
                if (!missile.isMotorBurningNow()) continue;
            }

            // 投影到屏幕
            Vec3 targetPos = missile.position();
            Vec3 screenPos = VectorUtil.worldToScreen(targetPos);
            if (screenPos == null || screenPos.z < 0) continue;

            double dist = mc.player.position().distanceTo(targetPos);
            String distStr = String.format("%.0fm", dist);
            RVP_WeaponData weaponData = RVP_RadarContactHelper.resolveBulletWeaponData(missile);
            // 最晚发射的自己烧完的导弹：超出配置范围时回退至上一个非 null 区间的文案
            boolean useFallback = (missile == latestBurnedOutOwn);
            String hudLabel = weaponData == null ? null
                    : (useFallback
                        ? weaponData.resolveMissileNameOnHudWithFallback((float) dist)
                        : weaponData.resolveMissileNameOnHud((float) dist));
            if (hudLabel == null || hudLabel.isBlank()) {
                hudLabel = "MSL";
            }

            int screenX = (int) screenPos.x;
            int screenY = (int) screenPos.y;

            // 忽略屏幕边缘外的目标（留 20px 边距）
            if (screenX < -20 || screenX > screenWidth + 20
                    || screenY < -20 || screenY > screenHeight + 20) continue;

            // 渲染红色菱形框
            drawDiamond(guiGraphics, screenX, screenY, DIAMOND_SIZE);

            // 菱形框下方显示 MSL 和距离（两行，居中对齐）
            int textX = screenX;
            String mslText = hudLabel + "  " + distStr;
            int textWidth = mc.font.width(mslText);
            guiGraphics.drawString(mc.font, mslText, textX - textWidth / 2, screenY + DIAMOND_SIZE + 2, LINE_COLOR);
        }
    }

    /**
     * 绘制红色空心菱形框（4 条粗边，每边一个四边形）。
     *
     * <p>2026-10-15 修复：原实现为 {@code GL11.glLineWidth(5.0f)} + DEBUG_LINE_STRIP。
     * 核心（forward-compatible）上下文下 glLineWidth(≠1.0) 非法 ⇒ 每次调用触发
     * GL_INVALID_VALUE（NVIDIA 驱动措辞 "Operation is not valid from a preview context"），
     * 实测有带 show_msl_indicator 的导弹时每秒刷出数十条错误；结尾的 disableBlend 还会
     * 把混合状态遗留给后续渲染（例如下一帧的座舱离屏绘制）。</p>
     *
     * <p>现改为纯四边形几何描边（与 glLineWidth 无关），退出时恢复进入前的混合状态，
     * 并计入座舱诊断计数（屏日志"相关性"行的 MSL框/秒）。</p>
     */
    private static void drawDiamond(GuiGraphics guiGraphics, int cx, int cy, int size) {
        boolean blendWasEnabled = GL11.glGetBoolean(GL11.GL_BLEND);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        float r = ((LINE_COLOR >> 16) & 0xFF) / 255.0f;
        float g = ((LINE_COLOR >> 8) & 0xFF) / 255.0f;
        float b = (LINE_COLOR & 0xFF) / 255.0f;
        float a = ((LINE_COLOR >> 24) & 0xFF) / 255.0f;

        // 菱形四顶点（与旧 DEBUG_LINE_STRIP 同序）：上→右→下→左→回上
        float[] xs = {cx, cx + size, cx, cx - size};
        float[] ys = {cy - size, cy, cy + size, cy};
        float half = DIAMOND_THICKNESS / 2.0f;
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4; i++) {
            float x1 = xs[i];
            float y1 = ys[i];
            float x2 = xs[(i + 1) % 4];
            float y2 = ys[(i + 1) % 4];
            float dx = x2 - x1;
            float dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1.0E-4F) {
                continue;
            }
            float nx = -dy / len * half;
            float ny = dx / len * half;
            buf.vertex(matrix, x1 - nx, y1 - ny, 0).color(r, g, b, a).endVertex();
            buf.vertex(matrix, x1 + nx, y1 + ny, 0).color(r, g, b, a).endVertex();
            buf.vertex(matrix, x2 + nx, y2 + ny, 0).color(r, g, b, a).endVertex();
            buf.vertex(matrix, x2 - nx, y2 - ny, 0).color(r, g, b, a).endVertex();
        }
        tess.end();
        if (!blendWasEnabled) {
            RenderSystem.disableBlend();
        }
        RVP_CockpitDiag.noteMslDiamond();
    }
}
