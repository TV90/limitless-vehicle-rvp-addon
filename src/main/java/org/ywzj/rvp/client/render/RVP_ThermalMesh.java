package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.ywzj.rvp.client.compat.acceleratedrendering.RVP_AcceleratedMeshCompat;
import org.ywzj.rvp.client.particle.RVP_ThermalParticleChannel;

/** 只接管本体当前 thermal_buffer 中的标准载具复绘，不更改 AR 阶段或热源判据。 */
public final class RVP_ThermalMesh {
    private RVP_ThermalMesh() { }

    public static Target current(MultiBufferSource source, RenderType material) {
        // 复用既有热成像链入口；热成像关闭时不查询 OpenGL。
        RenderTarget target = RVP_ThermalParticleChannel.activeThermalTarget();
        if (target == null || !RVP_AcceleratedMeshCompat.worldSharingConfigured()) return null;
        MultiBufferSource.BufferSource expected = Minecraft.getInstance().renderBuffers().bufferSource();
        if (target.frameBufferId < 0 || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != target.frameBufferId) return null;
        // 只允许原缓冲或消费者身份验证通过的 Iris 实体材质；其它顶点加工通道原样回退。
        RenderType actual = RVP_AcceleratedMeshCompat.entityMaterial(source, expected, material);
        return actual == null ? null : new Target(expected, target, actual);
    }

    /** 热源阶段拥有的缓冲与目标；只在当前绘制调用内保留，不能跨帧缓存。 */
    public record Target(MultiBufferSource.BufferSource source, RenderTarget framebuffer, RenderType material) {
        public void flush() {
            // 原消费者先提交 cube 的深度，再把即时 mesh 送回同一个热源目标。
            source.endBatch();
            framebuffer.bindWrite(true);
        }
    }
}
