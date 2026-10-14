package org.ywzj.rvp.client.cockpit;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 座舱屏幕画布（2026-10-14 座舱屏幕批）：把 GuiGraphics 级别的 2D 绘制离屏渲染进
 * {@link TextureTarget}，再把像素回灌进注册的 {@link DynamicTexture}——后者由
 * display 文件 {@code special_bone_effects} 的 {@code texture} 字段引用，本体验证
 * {@code TextureStateShard} 每帧按 RL 实时 bind，屏骨（cube/mesh 均可）自动显示内容。
 *
 * <p>性能约定：由 Manager 降频调用（雷达/RWR 20Hz、地图 5Hz）；回灌使用
 * {@code NativeImage.downloadTexture}（GPU→CPU 一次 1MB）+{@code upload()}，
 * 不做逐像素 Java 处理。Y 方向由绘制侧 pose 翻转（见 {@link #FLIP_DRAW_Y}）。</p>
 */
public final class RVP_CockpitCanvas {

    /**
     * 绘制侧 Y 翻转开关：离屏帧缓冲经 downloadTexture 后行序自底向上，与 Bedrock UV
     * （v=0 在顶）是否一致需实机验证；方向颠倒时改此开关即可，无需动回灌代码。
     */
    public static boolean FLIP_DRAW_Y = false;

    private RVP_CockpitCanvas() {}

    /** 内容绘制回调：在 0~width/0~height 逻辑像素坐标系内绘制（与普通 HUD 同构）。 */
    public interface Painter {
        void draw(GuiGraphics graphics, int width, int height);
    }

    /**
     * 创建一块指定尺寸的离屏渲染目标（带深度，供 GuiGraphics 的缓冲批次使用）。
     */
    public static TextureTarget createTarget(int size) {
        return new TextureTarget(size, size, true, false);
    }

    /**
     * 离屏渲染一帧内容并回灌到 DynamicTexture：
     * bindWrite → 清屏 → 正交投影（GUI 语义，y 向下）→ 内容绘制 → endBatch →
     * {@code downloadTexture} 回读 → {@code setPixels+upload} 上传。
     *
     * @param target 离屏渲染目标（尺寸须与 output 一致）
     * @param output 已注册的 DynamicTexture（special_bone_effects 引用的 RL 对象）
     * @param painter 内容绘制回调
     */
    public static void renderAndUpload(RenderTarget target, DynamicTexture output, Painter painter) {
        Minecraft mc = Minecraft.getInstance();
        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        // 调用 MC ModelView 栈：离屏 2D 渲染必须用单位 ModelView——ClientTick 阶段继承的
        // 是世界相机 ModelView，不重置会把内容画到相机变换后的远处（回灌全空=屏透明）。
        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        RenderSystem.applyModelViewMatrix();
        try {
            // 调用本项目离屏画布：绑定 RT 并清为熄屏底色（内容自绘背景覆盖）。
            target.setClearColor(0.04f, 0.05f, 0.06f, 1f);
            target.bindWrite(true);
            target.clear(Minecraft.ON_OSX);
            // 调用 MC GUI 同款正交投影：保证 GuiGraphics 的像素坐标语义与 HUD 完全一致。
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0, target.width, target.height, 0, 1000f, 21000f),
                    VertexSorting.ORTHOGRAPHIC_Z);
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
            GuiGraphics graphics = new GuiGraphics(mc, bufferSource);
            PoseStack pose = graphics.pose();
            if (FLIP_DRAW_Y) {
                // 调用本项目画布翻转开关：downloadTexture 行序自底向上，绘制侧整体镜像补偿。
                pose.scale(1f, -1f, 1f);
                pose.translate(0, -target.height, 0);
            }
            painter.draw(graphics, target.width, target.height);
            bufferSource.endBatch();
            // 回到主帧渲染目标，恢复主投影与 ModelView 矩阵（避免污染后续世界/HUD 渲染）。
            target.unbindWrite();
            mc.getMainRenderTarget().bindWrite(false);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            // 调用 NativeImage 回读：GPU→CPU 一次性下载当前帧（512²=1MB，降频调用）。
            var image = new com.mojang.blaze3d.platform.NativeImage(
                    com.mojang.blaze3d.platform.NativeImage.Format.RGBA,
                    target.width, target.height, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            // 调用本项目输出纹理：整体替换像素并上传（special_bone_effects 按 RL 实时 bind）。
            output.setPixels(image);
            output.upload();
        } catch (Exception exception) {
            // 单帧回灌失败不中断渲染循环：下一降频周期重试；日志节流由调用方诊断开关控制。
            java.io.StringWriter sw = new java.io.StringWriter();
            exception.printStackTrace(new java.io.PrintWriter(sw));
            org.ywzj.rvp.client.cockpit.RVP_CockpitDiag.log("renderAndUpload 失败: " + sw);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        } finally {
            // 调用 MC ModelView 栈：恢复世界渲染的 ModelView（与 push 配对）。
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * 全透明填充：非本车状态下 DynamicTexture 填 alpha=0——transparent 渲染下半透明
     * 片元完全不可见，视觉效果等同"屏骨整块不渲染"（2026-10-14 用户定案：
     * 非"渲染本车内容"即"不渲染"，不回退建模原 UV——屏 UV 占比大，原贴图过花）。
     * 注意：Transparent RenderType 通常写深度，极端视角下透明片元可能留空洞感，实机确认。
     */
    public static void fillTransparent(DynamicTexture texture, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                texture.getPixels().setPixelRGBA(x, y, 0x00000000);
            }
        }
        texture.upload();
    }
}
