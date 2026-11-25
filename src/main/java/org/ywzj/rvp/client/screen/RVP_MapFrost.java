package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** 每帧对地图做一次连续二分低通采样；所有玻璃共用最后一层，文字始终以原分辨率绘制。 */
final class RVP_MapFrost {
    /** 依据实际渲染分辨率和 GUI 比例复用缩小缓冲区；仅地图打开时占用显存。 */
    private static final RenderTarget[] levels = new RenderTarget[6];
    /** 当前帧可采样的最小层。 */
    private static RenderTarget backdrop;

    private RVP_MapFrost() {}

    static void capture(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget(), source = main;
        int targetWidth = Math.max(1, g.guiWidth() / 6);
        g.flush();
        for (int i = 0; i < levels.length && source.width > targetWidth; i++) {
            int w = Math.max(1, (source.width+1)/2), h = Math.max(1, (source.height+1)/2);
            RenderTarget dest = levels[i];
            if (dest == null || dest.width != w || dest.height != h) {
                if (dest != null) dest.destroyBuffers();
                levels[i] = dest = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                dest.setFilterMode(GL11.GL_LINEAR);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dest.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, source.viewWidth, source.viewHeight, 0, 0, w, h,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            source = dest;
        }
        main.bindWrite(true);
        backdrop = source == main ? null : source;
    }

    static void tile(GuiGraphics g, int left, int top, int right, int bottom) {
        if (backdrop == null || right <= left || bottom <= top) return;
        g.flush();
        float w = g.guiWidth(), h = g.guiHeight();
        var pose = g.pose().last().pose();
        float u = RVP_TacticalViewport.guiX(pose, left) / w;
        float u2 = RVP_TacticalViewport.guiX(pose, right) / w;
        float v = 1 - RVP_TacticalViewport.guiY(pose, top) / h;
        float v2 = 1 - RVP_TacticalViewport.guiY(pose, bottom) / h;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, backdrop.getColorTextureId());
        RenderSystem.setShaderColor(1,1,1,1);
        var buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(pose,left,top,0).uv(u,v).endVertex();
        buf.vertex(pose,left,bottom,0).uv(u,v2).endVertex();
        buf.vertex(pose,right,bottom,0).uv(u2,v2).endVertex();
        buf.vertex(pose,right,top,0).uv(u2,v).endVertex();
        BufferUploader.drawWithShader(buf.end());
    }

    static void release() {
        backdrop = null;
        for (int i=0; i<levels.length; i++) {
            if (levels[i]!=null) levels[i].destroyBuffers();
            levels[i]=null;
        }
    }
}
