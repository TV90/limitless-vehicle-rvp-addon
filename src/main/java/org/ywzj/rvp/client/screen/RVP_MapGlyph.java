package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/** 战术地图工具符号，以 GUI 像素描边，支持任意界面缩放。每个符号一次提交。 */
enum RVP_MapGlyph {
    GPS(new int[]{8,15,3,9,2,6,3,3,5,1,10,1,13,3,14,6,13,9,8,15}, new int[]{6,6,8,4,10,6,8,8,6,6}),
    RADAR(new int[]{8,1,12,2,15,6,15,10,12,14,8,15,4,14,1,10,1,6,4,2,8,1},
          new int[]{4,9,5,6,8,4,11,6}, new int[]{8,9,14,3}),
    CENTER(new int[]{2,6,2,2,6,2}, new int[]{10,2,14,2,14,6}, new int[]{14,10,14,14,10,14},
            new int[]{6,14,2,14,2,10}, new int[]{6,8,10,8}, new int[]{8,6,8,10}),
    FOLLOW(new int[]{2,7,14,2,9,14,7,9,2,7}),
    CLOSE(new int[]{3,3,13,13},new int[]{13,3,3,13}),
    DOWN(new int[]{3,6,8,11,13,6}),
    PATROL(new int[]{2,8,3,5,6,3,11,3,14,6,14,10,11,13,6,13,3,11},new int[]{0,6,2,9,5,7});

    /** 每条折线依次给出 16×16 图标中的 x、y 坐标。 */
    private final int[][] paths;
    RVP_MapGlyph(int[]... paths) { this.paths = paths; }

    void draw(GuiGraphics g, int cx, int cy, int size, int color) {
        g.flush();
        float scale=size/16f, stroke=Math.max(.55f,size*.046f);
        var matrix=g.pose().last().pose();
        var buf=Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int[] path:paths) for (int i=2; i<path.length; i+=2) {
            float x=cx+(path[i-2]-8)*scale, y=cy+(path[i-1]-8)*scale;
            float dx=(path[i]-path[i-2])*scale, dy=(path[i+1]-path[i-1])*scale;
            float k=stroke/(float)Math.hypot(dx,dy), nx=-dy*k, ny=dx*k;
            buf.vertex(matrix,x-nx,y-ny,0).color(color).endVertex();
            buf.vertex(matrix,x+nx,y+ny,0).color(color).endVertex();
            buf.vertex(matrix,x+dx+nx,y+dy+ny,0).color(color).endVertex();
            buf.vertex(matrix,x+dx-nx,y+dy-ny,0).color(color).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }
}
