package org.ywzj.rvp.client.compat.distanthorizons;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * RVP 自有的临时 framebuffer 集合。
 *
 * <p>DH 颜色/深度纹理只作为借用附件，永不删除、改尺寸或改采样参数。</p>
 */
final class RVP_DhFramebufferSet {
    /** 只借用 DH 深度纹理的复制源 FBO。 */
    private int depthSourceFramebuffer;
    /** 同时借用 DH 颜色与深度纹理的合成 FBO。 */
    private int compositeFramebuffer;
    /**
     * 每次接收 DH 当前纹理都重新附加并检查完整性。
     *
     * <p>DH 3.2.0 调整尺寸或重建资源时会先删除纹理再创建，驱动可能复用相同数值 ID。
     * 其它 FBO 仍可持有已删除名称对应的旧对象，因此不能按 ID 或尺寸相等跳过挂接。
     * 两个 FBO 都必须更新：否则会分别出现向旧对象写回或从旧深度对象复制的问题。</p>
     */
    void attachBorrowedTextures(int colorTexture, int depthTexture) {
        if (depthSourceFramebuffer == 0) {
            depthSourceFramebuffer = GL30.glGenFramebuffers();
        }
        if (compositeFramebuffer == 0) {
            compositeFramebuffer = GL30.glGenFramebuffers();
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthSourceFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        requireComplete("DH_DEPTH_SOURCE");

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, compositeFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colorTexture, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexture, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        requireComplete("DH_COMPOSITE");
    }

    /** 返回只读 DH 深度复制源 FBO。 */
    int depthSourceFramebuffer() {
        return depthSourceFramebuffer;
    }

    /** 返回借用 DH 颜色与深度附件的合成 FBO。 */
    int compositeFramebuffer() {
        return compositeFramebuffer;
    }

    /** 删除 RVP 创建的 FBO；绝不删除借用的 DH 纹理。 */
    void release() {
        if (depthSourceFramebuffer != 0) {
            GL30.glDeleteFramebuffers(depthSourceFramebuffer);
            depthSourceFramebuffer = 0;
        }
        if (compositeFramebuffer != 0) {
            GL30.glDeleteFramebuffers(compositeFramebuffer);
            compositeFramebuffer = 0;
        }
    }

    /** FBO 不完整时中止当前 pass，避免污染 DH 随后的 apply。 */
    private static void requireComplete(String name) {
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(name + " framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }
    }
}
