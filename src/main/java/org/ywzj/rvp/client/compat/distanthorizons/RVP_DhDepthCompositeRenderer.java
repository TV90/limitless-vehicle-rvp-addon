package org.ywzj.rvp.client.compat.distanthorizons;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFrameCoordinator;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFramePlan;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleVisualRenderer;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.config.RVP_ClientConfig.DistantHorizonsFallbackMode;

import java.io.IOException;
import java.util.Optional;

/** RVP 离屏绘制、DH 线性深度比较、颜色合成与 DH 深度回写入口。 */
public final class RVP_DhDepthCompositeRenderer {
    /** RVP 自有颜色/深度离屏目标。 */
    private static final RVP_RemoteVehicleOffscreenTarget OFFSCREEN_TARGET =
            new RVP_RemoteVehicleOffscreenTarget();
    /** DH 深度的 RVP 自有采样副本。 */
    private static final RVP_DhDepthCopy DH_DEPTH_COPY = new RVP_DhDepthCopy();
    /** 借用 DH 纹理的 RVP 自有临时 FBO。 */
    private static final RVP_DhFramebufferSet DH_FRAMEBUFFERS = new RVP_DhFramebufferSet();
    /** DH 深度感知合成 shader。 */
    private static ShaderInstance depthCompositeShader;
    /** 世界末端轮廓/始终可见降级 shader。 */
    private static ShaderInstance fallbackCompositeShader;
    /** RVP 逆投影 uniform。 */
    private static Uniform rvpInverseProjectionUniform;
    /** DH 逆投影 uniform。 */
    private static Uniform dhInverseProjectionUniform;
    /** DH 投影 uniform，用于把可见 RVP 像素重新编码为 DH 深度。 */
    private static Uniform dhProjectionUniform;
    /** DH 空深度 uniform。 */
    private static Uniform dhEmptyDepthUniform;
    /** DH near 深度 uniform。 */
    private static Uniform dhNearDepthUniform;
    /** DH far plane uniform，单位格。 */
    private static Uniform dhFarPlaneUniform;
    /** 遮挡容差 uniform，单位格。 */
    private static Uniform occlusionBiasUniform;
    /** 晚期降级模式 uniform：0 为轮廓，1 为完整图像。 */
    private static Uniform fallbackModeUniform;
    /** 晚期轮廓颜色 uniform。 */
    private static Uniform fallbackColorUniform;

    private RVP_DhDepthCompositeRenderer() {
    }

    /** 由 Forge 资源注册事件加载两套客户端 shader，并缓存自定义 uniform。 */
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                RVP_MOD.modLocation("dh_depth_composite"), DefaultVertexFormat.POSITION), loaded -> {
            depthCompositeShader = loaded;
            rvpInverseProjectionUniform = loaded.getUniform("RvpInverseProjection");
            dhInverseProjectionUniform = loaded.getUniform("DhInverseProjection");
            dhProjectionUniform = loaded.getUniform("DhProjection");
            dhEmptyDepthUniform = loaded.getUniform("DhEmptyDepth");
            dhNearDepthUniform = loaded.getUniform("DhNearDepth");
            dhFarPlaneUniform = loaded.getUniform("DhFarPlane");
            occlusionBiasUniform = loaded.getUniform("OcclusionBiasBlocks");
        });
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                RVP_MOD.modLocation("dh_fallback_composite"), DefaultVertexFormat.POSITION), loaded -> {
            fallbackCompositeShader = loaded;
            fallbackModeUniform = loaded.getUniform("FallbackMode");
            fallbackColorUniform = loaded.getUniform("FallbackColor");
        });
    }

    /**
     * 在 DH apply 前把当前计划绘制到 RVP 离屏目标，并按线性视深度合入借用 DH 纹理。
     *
     * @return 颜色与深度均成功写回时返回 true
     */
    public static boolean composite(RVP_RemoteVehicleFramePlan plan,
                                    Camera camera,
                                    RVP_DhRenderParameters dhParameters,
                                    int dhColorTexture,
                                    int dhDepthTexture) {
        RenderSystem.assertOnRenderThread();
        if (depthCompositeShader == null || plan.projectionPlan() == null) {
            return fail("UNSUPPORTED_PROJECTION", "shader/projection unavailable");
        }
        Matrix4f rvpProjection = plan.projectionPlan().projection();
        Optional<RVP_DhProjectionMath.ProjectionAnalysis> rvpAnalysis =
                RVP_DhProjectionMath.analyze(rvpProjection,
                        (float) plan.projectionPlan().nearPlane(),
                        (float) plan.projectionPlan().requiredFarPlane());
        Optional<RVP_DhProjectionMath.ProjectionAnalysis> dhAnalysis =
                RVP_DhProjectionMath.analyze(dhParameters.projection(),
                        dhParameters.nearPlane(), dhParameters.farPlane());
        if (rvpAnalysis.isEmpty() || dhAnalysis.isEmpty()) {
            return fail("UNSUPPORTED_PROJECTION", "near/far endpoint validation failed");
        }

        long started = System.nanoTime();
        try (RVP_DhGlStateScope ignored = RVP_DhGlStateScope.capture()) {
            int width = textureDimension(dhColorTexture, GL11.GL_TEXTURE_WIDTH);
            int height = textureDimension(dhColorTexture, GL11.GL_TEXTURE_HEIGHT);
            int depthWidth = textureDimension(dhDepthTexture, GL11.GL_TEXTURE_WIDTH);
            int depthHeight = textureDimension(dhDepthTexture, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width != depthWidth || height != depthHeight) {
                return fail("TEXTURE_UNAVAILABLE", "color=" + width + "x" + height
                        + ", depth=" + depthWidth + "x" + depthHeight);
            }

            OFFSCREEN_TARGET.ensureSize(width, height);
            DH_DEPTH_COPY.ensureSize(width, height);
            DH_FRAMEBUFFERS.attachBorrowedTextures(dhColorTexture, dhDepthTexture);

            OFFSCREEN_TARGET.bindAndClear();
            // 调用本项目目标无关渲染器，使正常模型、LOD、槽位图与动态快照走同一离屏通道。
            RVP_RemoteVehicleVisualRenderer.renderPrepared(plan, camera, true);
            // 调用本项目 DH 深度复制器，避免采样和写回同一 DH 深度纹理。
            DH_DEPTH_COPY.copyFrom(DH_FRAMEBUFFERS.depthSourceFramebuffer());

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, DH_FRAMEBUFFERS.compositeFramebuffer());
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColorMask(true, true, true, true);

            RenderSystem.setShader(() -> depthCompositeShader);
            depthCompositeShader.setSampler("RvpColor", OFFSCREEN_TARGET.colorTextureId());
            depthCompositeShader.setSampler("RvpDepth", OFFSCREEN_TARGET.depthTextureId());
            depthCompositeShader.setSampler("DhDepthCopy", DH_DEPTH_COPY.textureId());
            setMatrix(rvpInverseProjectionUniform, rvpAnalysis.get().inverseProjection());
            setMatrix(dhInverseProjectionUniform, dhAnalysis.get().inverseProjection());
            setMatrix(dhProjectionUniform, dhParameters.projection());
            setFloat(dhEmptyDepthUniform, dhAnalysis.get().emptyDepth());
            setFloat(dhNearDepthUniform, dhAnalysis.get().nearDepth());
            setFloat(dhFarPlaneUniform, dhParameters.farPlane());
            setFloat(occlusionBiasUniform,
                    RVP_ClientConfig.getDistantHorizonsOcclusionBiasBlocks());
            drawFullscreenQuad();

            double milliseconds = (System.nanoTime() - started) / 1_000_000.0D;
            // 调用本项目诊断器，按配置与一秒频率限制输出合成状态。
            RVP_DhCompatDiagnostics.recordComposite(
                    dhAnalysis.get().reverseZ() ? "REVERSE_Z" : "FORWARD_Z",
                    dhParameters.renderPass(), plan.selectedCount(), milliseconds);
            return true;
        }
    }

    /** 在当前世界最终目标上绘制轮廓或显式始终可见图像，不读取/写入地形深度。 */
    public static void renderLateFallback(RVP_RemoteVehicleFramePlan plan,
                                          Camera camera,
                                          DistantHorizonsFallbackMode fallbackMode) {
        RenderSystem.assertOnRenderThread();
        if (fallbackCompositeShader == null || plan == null) {
            fail("TEXTURE_UNAVAILABLE", "fallback shader unavailable");
            return;
        }
        try (RVP_DhGlStateScope ignored = RVP_DhGlStateScope.capture()) {
            int destinationFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int[] viewport = currentViewport();
            if (viewport[2] <= 0 || viewport[3] <= 0) {
                return;
            }
            OFFSCREEN_TARGET.ensureSize(viewport[2], viewport[3]);
            OFFSCREEN_TARGET.bindAndClear();
            // 调用本项目目标无关渲染器，先得到完整载具 alpha/颜色，再由晚期 shader 选择边缘或整图。
            RVP_RemoteVehicleVisualRenderer.renderPrepared(plan, camera, true);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, destinationFramebuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);

            RenderSystem.setShader(() -> fallbackCompositeShader);
            fallbackCompositeShader.setSampler("RvpColor", OFFSCREEN_TARGET.colorTextureId());
            setInt(fallbackModeUniform,
                    fallbackMode == DistantHorizonsFallbackMode.ALWAYS_VISIBLE ? 1 : 0);
            setVec4(fallbackColorUniform, 0.0F, 0.95F, 1.0F, 0.82F);
            drawFullscreenQuad();
        }
    }

    /** 释放且只释放 RVP 自有 FBO/纹理；DH 借用纹理从不删除。 */
    public static void release() {
        RenderSystem.assertOnRenderThread();
        OFFSCREEN_TARGET.release();
        DH_DEPTH_COPY.release();
        DH_FRAMEBUFFERS.release();
    }

    /** 查询二维纹理某一 mip0 尺寸；DH ID 每次事件重新传入，不做永久缓存。 */
    private static int textureDimension(int textureId, int parameter) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, parameter);
    }

    /** 读取当前 viewport。 */
    private static int[] currentViewport() {
        java.nio.IntBuffer buffer = org.lwjgl.BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, buffer);
        return new int[]{buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3)};
    }

    /** 使用当前 ShaderInstance 绘制覆盖 NDC 的四边形。 */
    private static void drawFullscreenQuad() {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.vertex(-1.0D, -1.0D, 0.0D).endVertex();
        builder.vertex(1.0D, -1.0D, 0.0D).endVertex();
        builder.vertex(1.0D, 1.0D, 0.0D).endVertex();
        builder.vertex(-1.0D, 1.0D, 0.0D).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }

    /** 设置矩阵 uniform；资源缺失时由 shader 默认值保持安全。 */
    private static void setMatrix(Uniform uniform, Matrix4f value) {
        if (uniform != null) {
            uniform.set(value);
        }
    }

    /** 设置浮点 uniform。 */
    private static void setFloat(Uniform uniform, float value) {
        if (uniform != null) {
            uniform.set(value);
        }
    }

    /** 设置整数 uniform。 */
    private static void setInt(Uniform uniform, int value) {
        if (uniform != null) {
            uniform.set(value);
        }
    }

    /** 设置四分量颜色 uniform。 */
    private static void setVec4(Uniform uniform, float red, float green, float blue, float alpha) {
        if (uniform != null) {
            uniform.set(red, green, blue, alpha);
        }
    }

    /** 标记不可用原因并返回 false，便于合成入口立即进入显式降级。 */
    private static boolean fail(String reason, String detail) {
        RVP_RemoteVehicleFrameCoordinator.markDhCompositeFailure(reason);
        RVP_DhCompatDiagnostics.warnOnce(reason, detail);
        return false;
    }
}
