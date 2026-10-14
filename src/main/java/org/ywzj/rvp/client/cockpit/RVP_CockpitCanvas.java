package org.ywzj.rvp.client.cockpit;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;

import java.io.File;

/**
 * 座舱屏幕画布（2026-10-14 座舱屏幕批，方案 B 定案）：把 GuiGraphics 级别的 2D 绘制
 * 离屏渲染进 {@link TextureTarget}，屏骨纹理**直接包装 RT 颜色纹理采样**——
 * 零回读、零上传（见 {@link RVP_CockpitScreenManager} 的纹理包装）。
 *
 * <p>历史：曾用 downloadTexture→DynamicTexture 回灌管线，栽在 DynamicTexture GL
 * 生命周期上（首次进世界不显示、F3+T 后恢复、CPU 侧数据与 GPU 侧不一致），2026-10-14
 * 定案方案 B 消灭回灌环节。性能约定：由 Manager 降频调用（雷达/RWR 20Hz、地图 5Hz）。</p>
 */
public final class RVP_CockpitCanvas {

    /**
     * 绘制侧 Y 翻转开关：离屏 RT 的纹理 v 轴与 GUI y 轴方向相反（GL 帧缓冲原点在左下），
     * 直采时屏骨画面默认上下颠倒——2026-10-14 用户实机确认颠倒，默认开启补偿。
     */
    public static boolean FLIP_DRAW_Y = true;

    /** 画布诊断节流（shaderColor 残留值每秒至多一条）。 */
    private static long lastCanvasDiagMs;

    private RVP_CockpitCanvas() {}

    /** 屏骨文字放大倍率（512² 画布上默认 9px 字体在实机屏上过小，统一 2 倍）。 */
    public static final float FONT_SCALE = 2.0f;

    /**
     * 以 (cx,cy) 为中心、按 {@link #FONT_SCALE} 放大绘制居中文字
     * （GuiGraphics 无字号参数，标准做法=平移到锚点后缩放绘制）。
     */
    public static void drawScaledCenteredString(GuiGraphics graphics, PoseStack pose,
                                                String text, int cx, int cy, int color) {
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.scale(FONT_SCALE, FONT_SCALE, 1.0F);
        graphics.drawCenteredString(
                net.minecraft.client.Minecraft.getInstance().font, text, 0, -4, color);
        pose.popPose();
    }

    /** 内容绘制回调：在 0~width/0~height 逻辑像素坐标系内绘制（与普通 HUD 同构）。 */
    public interface Painter {
        void draw(GuiGraphics graphics, int width, int height);
    }

    // ==== 离屏 RT 图元（立即模式 position_color）====
    // 说明：GuiHelper.drawCircle 的 HUD_CIRCLE 自定义 shader 在离屏 RT 下失效
    // （radar 圈/盘全丢、rwr 圆盘 alpha=0，2026-10-14 dump 实测），改用与 fill 同款
    // 的 position_color 立即路径自绘（已验证 RT 下写入可靠）。

    /** 当前图元颜色（setupPositionColor 设置，顶点提交读取）。 */
    private static float currentR;
    private static float currentG;
    private static float currentB;
    private static float currentA;

    private static void setupPositionColor(int argb) {
        currentA = (argb >>> 24 & 255) / 255.0F;
        currentR = (argb >> 16 & 255) / 255.0F;
        currentG = (argb >> 8 & 255) / 255.0F;
        currentB = (argb & 255) / 255.0F;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
    }

    /** 全屏/矩形纯色填充（立即模式）。 */
    public static void fillRectImmediate(PoseStack pose, float x1, float y1, float x2, float y2, int argb) {
        var t = Tesselator.getInstance();
        var buf = t.getBuilder();
        Matrix4f mat = pose.last().pose();
        setupPositionColor(argb);
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(mat, x1, y2, 0).color(currentR, currentG, currentB, currentA).endVertex();
        buf.vertex(mat, x2, y2, 0).color(currentR, currentG, currentB, currentA).endVertex();
        buf.vertex(mat, x2, y1, 0).color(currentR, currentG, currentB, currentA).endVertex();
        buf.vertex(mat, x1, y1, 0).color(currentR, currentG, currentB, currentA).endVertex();
        t.end();
    }

    /** 实心圆（立即模式，48 段三角扇）。 */
    public static void drawDiscImmediate(PoseStack pose, float cx, float cy, float radius, int argb) {
        var t = Tesselator.getInstance();
        var buf = t.getBuilder();
        Matrix4f mat = pose.last().pose();
        setupPositionColor(argb);
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int segments = 48;
        float prevX = cx + radius, prevY = cy;
        for (int i = 1; i <= segments; i++) {
            float angle = (float) (i * Math.PI * 2 / segments);
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            buf.vertex(mat, cx, cy, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, prevX, prevY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, px, py, 0).color(currentR, currentG, currentB, currentA).endVertex();
            prevX = px;
            prevY = py;
        }
        t.end();
    }

    /** 圆环（立即模式，外径/内径三角带）。 */
    public static void drawRingImmediate(PoseStack pose, float cx, float cy,
                                         float outerRadius, float innerRadius, int argb) {
        var t = Tesselator.getInstance();
        var buf = t.getBuilder();
        Matrix4f mat = pose.last().pose();
        setupPositionColor(argb);
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int segments = 48;
        float prevOX = cx + outerRadius, prevOY = cy;
        float prevIX = cx + innerRadius, prevIY = cy;
        for (int i = 1; i <= segments; i++) {
            float angle = (float) (i * Math.PI * 2 / segments);
            float oX = cx + (float) Math.cos(angle) * outerRadius;
            float oY = cy + (float) Math.sin(angle) * outerRadius;
            float iX = cx + (float) Math.cos(angle) * innerRadius;
            float iY = cy + (float) Math.sin(angle) * innerRadius;
            buf.vertex(mat, prevOX, prevOY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, prevIX, prevIY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, oX, oY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, prevOX, prevOY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, iX, iY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, oX, oY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            prevOX = oX;
            prevOY = oY;
            prevIX = iX;
            prevIY = iY;
        }
        t.end();
    }

    /**
     * 扇形（立即模式，中心+弧点三角形扇）。
     * <p>角度语义与本体 HUD 雷达扫描线一致（VehicleRadarOverlay.drawScanLine）：
     * 0°=屏幕正上（车头），角度顺时针增大；startDeg/endDeg 为雷达 yRotMin/yRotMax（度）。</p>
     */
    public static void drawSectorImmediate(PoseStack pose, float cx, float cy, float radius,
                                           float startDeg, float endDeg, int argb) {
        var t = Tesselator.getInstance();
        var buf = t.getBuilder();
        Matrix4f mat = pose.last().pose();
        setupPositionColor(argb);
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int segments = 48;
        float prevX = cx + (float) Math.cos(Math.toRadians(startDeg - 90)) * radius;
        float prevY = cy + (float) Math.sin(Math.toRadians(startDeg - 90)) * radius;
        for (int i = 1; i <= segments; i++) {
            float deg = startDeg + (endDeg - startDeg) * i / segments;
            float px = cx + (float) Math.cos(Math.toRadians(deg - 90)) * radius;
            float py = cy + (float) Math.sin(Math.toRadians(deg - 90)) * radius;
            buf.vertex(mat, cx, cy, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, prevX, prevY, 0).color(currentR, currentG, currentB, currentA).endVertex();
            buf.vertex(mat, px, py, 0).color(currentR, currentG, currentB, currentA).endVertex();
            prevX = px;
            prevY = py;
        }
        t.end();
    }

    /** 线段（立即模式，指定宽度的四边形）。 */
    public static void drawLineImmediate(PoseStack pose, float x1, float y1, float x2, float y2,
                                         float width, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-4F) {
            return;
        }
        float nx = -dy / len * (width / 2f);
        float ny = dx / len * (width / 2f);
        var t = Tesselator.getInstance();
        var buf = t.getBuilder();
        Matrix4f mat = pose.last().pose();
        setupPositionColor(argb);
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(mat, x1 - nx, y1 - ny, 0).color(currentR, currentG, currentB, currentA).endVertex();
        buf.vertex(mat, x1 + nx, y1 + ny, 0).color(currentR, currentG, currentB, currentA).endVertex();
        buf.vertex(mat, x2 + nx, y2 + ny, 0).color(currentR, currentG, currentB, currentA).endVertex();
        buf.vertex(mat, x2 - nx, y2 - ny, 0).color(currentR, currentG, currentB, currentA).endVertex();
        t.end();
    }

    /** 创建一块指定尺寸的离屏渲染目标（带深度，供 GuiGraphics 的缓冲批次使用）。 */
    public static TextureTarget createTarget(int size) {
        return new TextureTarget(size, size, true, false);
    }

    /**
     * 离屏渲染一帧内容到 RT（屏骨纹理直接采样该 RT 颜色纹理）：
     * clear → 正交投影（GUI 语义，y 向下）→ 内容绘制 → endBatch。
     * 不做任何回读/上传（方案 B：屏骨纹理=RT 颜色纹理直采）。
     */
    public static void renderToTarget(RenderTarget target, Painter painter) {
        renderToTarget(target, "-", painter);
    }

    /**
     * 离屏渲染（带屏型 label）：与无 label 版完全同逻辑，仅用于把异常归档到对应屏
     * （诊断 v2：冻结时按屏型统计 fails 并按节流输出堆栈，避免三屏混在一起看不出
     * 到底是哪块屏挂了）。
     */
    public static void renderToTarget(RenderTarget target, String label, Painter painter) {
        Minecraft mc = Minecraft.getInstance();
        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        float[] savedShaderColor = null;
        // 调用 MC ModelView 栈：离屏 2D 渲染必须用单位 ModelView + 原版 GUI 同款 z=-11000
        // 平移——setOrtho(0,w,h,0,1000,21000) 的可见 z 范围是 [-21000,-1000]，不平移整批片元
        // 被近裁剪面裁掉（2026-10-14 实锤）。
        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        modelViewStack.translate(0.0F, 0.0F, -11000.0F);
        RenderSystem.applyModelViewMatrix();
        try {
            // 调用 RT.clear 清屏：注意 1.20.1 RenderTarget.clear() 末尾会 unbindWrite 解绑
            // FBO（字节码实锤），清完必须重新 bindWrite 再绘制，否则全部绘制画进主画布
            // （2026-10-14 实锤：clear 后不重绑 = RT 只剩清屏色）。
            // 强制颜色掩码全开：ClientTick 时点可能残留 alpha 位=false 的 colorMask（其它渲染
            // 路径遗留），会导致 RT 的 alpha 通道永远写不进去（恒为 clear 值）——屏骨混合时
            // 整体透出底下机体骨（2026-10-14 实机"四角透"实锤根源之一）。
            RenderSystem.colorMask(true, true, true, true);
            target.setClearColor(0.04f, 0.05f, 0.06f, 1f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            // 调用 RenderSystem 复位着色器颜色/剪裁：shaderColor（ColorModulator）不属于任何
            // RenderType.setupRenderState 的复位范围，ClientTick 时点继承上一帧残留值会污染画布。
            savedShaderColor = RenderSystem.getShaderColor();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableScissor();
            long nowDiagMs = System.currentTimeMillis();
            if (RVP_CockpitDiag.isEnabled() && nowDiagMs - lastCanvasDiagMs >= 1000) {
                lastCanvasDiagMs = nowDiagMs;
                RVP_CockpitDiag.log("画布诊断 残留shaderColor="
                        + (savedShaderColor == null ? "null" : java.util.Arrays.toString(savedShaderColor)));
            }
            // 调用 MC GUI 同款正交投影：保证 GuiGraphics 的像素坐标语义与 HUD 完全一致。
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0, target.width, target.height, 0, 1000f, 21000f),
                    VertexSorting.ORTHOGRAPHIC_Z);
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
            GuiGraphics graphics = new GuiGraphics(mc, bufferSource);
            PoseStack pose = graphics.pose();
            if (FLIP_DRAW_Y) {
                // 调用本项目画布翻转开关：RT 纹理 v 轴与 GUI y 轴方向相反，直采时画面上下颠倒。
                // PoseStack 语义=后调用先作用于顶点：必须【先 translate(+h) 后 scale(-1)】，
                // 顶点映射 y' = h - y（留在 [0,h] 视口内）；顺序写反（scale 在前）会把内容
                // 变换到 -y 区间整批裁掉——RT 只剩清屏黑底（2026-10-16 实锤）。
                pose.translate(0, target.height, 0);
                pose.scale(1f, -1f, 1f);
            }
            painter.draw(graphics, target.width, target.height);
            bufferSource.endBatch();
            // 异常路径同样恢复残留着色器颜色（与正常路径配对，避免半途状态污染主渲染）。
            if (savedShaderColor != null) {
                RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1],
                        savedShaderColor[2], savedShaderColor[3]);
            }
            // 回到主帧渲染目标，恢复主投影与 ModelView 矩阵（避免污染后续世界/HUD 渲染）。
            target.unbindWrite();
            mc.getMainRenderTarget().bindWrite(false);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        } catch (Exception exception) {
            // 单帧渲染失败不中断渲染循环：下一降频周期重试；日志节流由调用方诊断开关控制。
            java.io.StringWriter sw = new java.io.StringWriter();
            exception.printStackTrace(new java.io.PrintWriter(sw));
            RVP_CockpitDiag.log("renderToTarget 失败[" + label + "]: " + sw);
            RVP_CockpitDiag.noteFail(label, exception);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            if (savedShaderColor != null) {
                RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1],
                        savedShaderColor[2], savedShaderColor[3]);
            }
        } finally {
            // 调用 MC ModelView 栈：恢复世界渲染的 ModelView（与 push 配对）。
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * 诊断专用：把 RT 当前 GPU 帧下载为 PNG 文件（每 2 秒一轮，仅排查期使用）。
     *
     * @return 是否成功
     */
    public static boolean dumpTargetPNG(RenderTarget target, File file) {
        try {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA,
                    target.width, target.height, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.writeToFile(file);
            image.close();
            return true;
        } catch (Exception exception) {
            RVP_CockpitDiag.log("PNG dump 失败: " + exception);
            return false;
        }
    }
}
