package org.ywzj.rvp.client.compat.distanthorizons;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** RVP 独占的 RGBA8 颜色、DEPTH32F 深度与 framebuffer。 */
final class RVP_RemoteVehicleOffscreenTarget {
    /** RVP 自有 framebuffer ID。 */
    private int framebufferId;
    /** RVP 自有 RGBA8 颜色纹理 ID。 */
    private int colorTextureId;
    /** RVP 自有 DEPTH32F 深度纹理 ID。 */
    private int depthTextureId;
    /** 当前资源宽度。 */
    private int width;
    /** 当前资源高度。 */
    private int height;

    /** 确保资源与目标 viewport 同尺寸；尺寸变化时只重建 RVP 自有对象。 */
    void ensureSize(int requestedWidth, int requestedHeight) {
        RenderSystem.assertOnRenderThread();
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("invalid RVP offscreen size "
                    + requestedWidth + "x" + requestedHeight);
        }
        if (framebufferId != 0 && width == requestedWidth && height == requestedHeight) {
            return;
        }
        release();
        width = requestedWidth;
        height = requestedHeight;
        colorTextureId = createTexture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
        depthTextureId = createTexture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
        framebufferId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colorTextureId, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTextureId, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        requireComplete("RVP_OFFSCREEN");
    }

    /** 绑定为绘制目标，并只清除 RVP 自有颜色与深度。 */
    void bindAndClear() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GL11.glClearDepth(1.0D);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /** 返回 RVP 自有 FBO，供诊断核对模型提交后的真实 draw 目标。 */
    int framebufferId() {
        return framebufferId;
    }

    /** 返回 RVP 自有颜色纹理 ID。 */
    int colorTextureId() {
        return colorTextureId;
    }

    /** 返回 RVP 自有深度纹理 ID。 */
    int depthTextureId() {
        return depthTextureId;
    }

    /** 返回当前宽度。 */
    int width() {
        return width;
    }

    /** 返回当前高度。 */
    int height() {
        return height;
    }

    /** 删除且只删除 RVP 创建的 framebuffer 与纹理。 */
    void release() {
        if (framebufferId != 0) {
            GL30.glDeleteFramebuffers(framebufferId);
            framebufferId = 0;
        }
        if (colorTextureId != 0) {
            GL11.glDeleteTextures(colorTextureId);
            colorTextureId = 0;
        }
        if (depthTextureId != 0) {
            GL11.glDeleteTextures(depthTextureId);
            depthTextureId = 0;
        }
        width = 0;
        height = 0;
    }

    /** 创建一张属于 RVP 的二维纹理并配置无 mipmap 的安全采样参数。 */
    private int createTexture(int internalFormat, int format, int type) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height,
                0, format, type, 0L);
        return textureId;
    }

    /** FBO 不完整时立即中止当前兼容 pass，交由上层显式降级。 */
    private static void requireComplete(String name) {
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(name + " framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }
    }
}
