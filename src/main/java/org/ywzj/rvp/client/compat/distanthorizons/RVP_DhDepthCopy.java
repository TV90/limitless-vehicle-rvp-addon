package org.ywzj.rvp.client.compat.distanthorizons;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/** 把 DH 深度复制到 RVP 自有 DEPTH32F，消除采样/写回同纹理的未定义反馈环。 */
final class RVP_DhDepthCopy {
    /** 深度副本纹理 ID。 */
    private int textureId;
    /** 持有深度副本附件的 RVP 自有 FBO。 */
    private int framebufferId;
    /** 当前副本宽度。 */
    private int width;
    /** 当前副本高度。 */
    private int height;

    /** 确保深度副本与 DH 纹理同尺寸。 */
    void ensureSize(int requestedWidth, int requestedHeight) {
        if (textureId != 0 && width == requestedWidth && height == requestedHeight) {
            return;
        }
        release();
        width = requestedWidth;
        height = requestedHeight;
        textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
        framebufferId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, textureId, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("RVP_DH_DEPTH_COPY framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }
    }

    /** 从只附加 DH 深度的借用 FBO blit 到 RVP 自有深度副本。 */
    void copyFrom(int sourceFramebuffer) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebufferId);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
    }

    /** 返回只属于 RVP 的可采样深度副本纹理。 */
    int textureId() {
        return textureId;
    }

    /** 删除 RVP 自有深度副本资源。 */
    void release() {
        if (framebufferId != 0) {
            GL30.glDeleteFramebuffers(framebufferId);
            framebufferId = 0;
        }
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
        width = 0;
        height = 0;
    }
}
