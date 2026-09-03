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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFramePlan;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleVisualRenderer;
import org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect.RVP_DhTrackedVehicleFramePlan;
import org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect.RVP_DhTrackedVehicleProtectionRenderer;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.config.RVP_ClientConfig.DistantHorizonsFallbackMode;

import java.io.IOException;
import java.util.Optional;

/** RVP 离屏绘制、DH 线性深度比较、颜色合成与 DH 深度回写入口。 */
public final class RVP_DhDepthCompositeRenderer {
    /** GPU 通过样本诊断的最小采集间隔，避免同步查询逐帧阻塞渲染线程。 */
    private static final long LAYER_SAMPLE_CAPTURE_INTERVAL_NANOS = 1_000_000_000L;
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
    /** 诊断模式 uniform：1 时只统计离屏颜色中非透明像素，不执行 DH 深度遮挡。 */
    private static Uniform diagnosticBypassOcclusionUniform;
    /** 分层染色 uniform：0 原色、1 青色远距、2 品红真实载具；不改写实际深度。 */
    private static Uniform diagnosticLayerColorModeUniform;
    /** 晚期降级模式 uniform：0 为轮廓，1 为完整图像。 */
    private static Uniform fallbackModeUniform;
    /** 晚期轮廓颜色 uniform。 */
    private static Uniform fallbackColorUniform;
    /** 上次执行分层 GPU 通过样本诊断的时间。 */
    private static long lastLayerSampleCaptureNanos;

    private RVP_DhDepthCompositeRenderer() {
    }

    /** 由 Forge 资源注册事件加载两套客户端 shader，并缓存自定义 uniform。 */
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        // 调用本项目观察型 shader，以继承方式在 apply 完成后读取真实状态，不添加注入点。
        event.registerShader(new RVP_DhPixelDiagnostics.ObservedShader(event.getResourceProvider(),
                RVP_MOD.modLocation("dh_depth_composite")), loaded -> {
            depthCompositeShader = loaded;
            rvpInverseProjectionUniform = loaded.getUniform("RvpInverseProjection");
            dhInverseProjectionUniform = loaded.getUniform("DhInverseProjection");
            dhProjectionUniform = loaded.getUniform("DhProjection");
            dhEmptyDepthUniform = loaded.getUniform("DhEmptyDepth");
            dhNearDepthUniform = loaded.getUniform("DhNearDepth");
            dhFarPlaneUniform = loaded.getUniform("DhFarPlane");
            occlusionBiasUniform = loaded.getUniform("OcclusionBiasBlocks");
            diagnosticBypassOcclusionUniform = loaded.getUniform("DiagnosticBypassOcclusion");
            diagnosticLayerColorModeUniform = loaded.getUniform("DiagnosticLayerColorMode");
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
     * @return 合成调用完成且未检测到异常时返回 true；实际像素与视觉效果另由诊断/实机验证
     */
    public static boolean composite(RVP_DhVehicleFramePlan plan,
                                    Camera camera,
                                    RVP_DhRenderParameters dhParameters,
                                    int dhColorTexture,
                                    int dhDepthTexture) {
        RenderSystem.assertOnRenderThread();
        if (depthCompositeShader == null || plan == null) {
            return fail("UNSUPPORTED_PROJECTION", "shader/frame unavailable");
        }
        // 调用本项目投影数学工具，从 DH 矩阵恢复真实裁剪面；DH 事件 near 是过度绘制距离，可能与矩阵 near 不同。
        Optional<RVP_DhProjectionMath.ProjectionAnalysis> dhAnalysis =
                RVP_DhProjectionMath.analyzeFromProjection(dhParameters.projection());
        if (dhAnalysis.isEmpty()) {
            return fail("UNSUPPORTED_PROJECTION", "DH matrix endpoint recovery failed, reportedNear="
                    + dhParameters.nearPlane() + ", reportedFar=" + dhParameters.farPlane());
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
            boolean captureLayerSamples = shouldCaptureLayerSamples();
            // 调用限频像素取证，关联当前帧离屏、DH 写回及世界最终目标。
            RVP_DhPixelDiagnostics.begin(captureLayerSamples, width, height,
                    DH_FRAMEBUFFERS.compositeFramebuffer(), dhColorTexture, dhDepthTexture);
            int remoteAlphaSamples = -1;
            int remotePassedSamples = -1;
            int trackedAlphaSamples = -1;
            int trackedPassedSamples = -1;

            RVP_RemoteVehicleFramePlan remotePlan = plan.remotePlan();
            if (remotePlan != null) {
                if (remotePlan.projectionPlan() == null) {
                    return fail("REMOTE_PROJECTION_UNAVAILABLE", "remote projection unavailable");
                }
                Matrix4f remoteProjection = remotePlan.projectionPlan().offscreenProjection();
                // 调用投影数学工具，以远距层独立 near/far 校验其高精度离屏投影。
                Optional<RVP_DhProjectionMath.ProjectionAnalysis> remoteAnalysis =
                        RVP_DhProjectionMath.analyze(remoteProjection,
                                (float) remotePlan.projectionPlan().offscreenNearPlane(),
                                (float) remotePlan.projectionPlan().offscreenFarPlane());
                if (remoteAnalysis.isEmpty()) {
                    return fail("REMOTE_PROJECTION_UNAVAILABLE",
                            "remote near/far endpoint validation failed");
                }
                // 调用通用分层合成：先写远距代理，使后续 tracked 层可读取已更新的 DH 深度。
                LayerSampleCounts remoteSamples = compositeLayer(remoteAnalysis.get(), dhAnalysis.get(),
                        dhParameters.projection(),
                        width, height,
                        () -> RVP_RemoteVehicleVisualRenderer.renderPrepared(remotePlan, camera, true),
                        RVP_ClientConfig.getDistantHorizonsOcclusionBiasBlocks(), captureLayerSamples, "remote");
                remoteAlphaSamples = remoteSamples.alphaSamples();
                remotePassedSamples = remoteSamples.passedSamples();
            }

            RVP_DhTrackedVehicleFramePlan trackedPlan = plan.trackedPlan();
            if (trackedPlan != null) {
                // 调用投影数学工具，从当帧 Minecraft 投影恢复真实载具层的独立视深度参数。
                Optional<RVP_DhProjectionMath.ProjectionAnalysis> trackedAnalysis =
                        RVP_DhProjectionMath.analyzeFromProjection(trackedPlan.projection());
                if (trackedAnalysis.isEmpty()) {
                    return fail("TRACKED_PROJECTION_UNAVAILABLE",
                            "tracked Minecraft projection validation failed");
                }
                // 调用通用分层合成：刷新已含 remote 的 DH 深度后再合入真实载具。
                LayerSampleCounts trackedSamples = compositeLayer(trackedAnalysis.get(), dhAnalysis.get(),
                        dhParameters.projection(),
                        width, height,
                        () -> {
                            // 调用限频取光对照，关联目标实体与离屏暗色；关闭 diagnostics 时直接返回。
                            RVP_DhPixelDiagnostics.trackedLighting(trackedPlan);
                            // 调用只读主体绘制，使用计划内与正常实体渲染一致的冻结光照。
                            RVP_DhTrackedVehicleProtectionRenderer.renderPrepared(trackedPlan);
                        },
                        RVP_ClientConfig.getDistantHorizonsTrackedOcclusionBiasBlocks(),
                        captureLayerSamples, "tracked");
                trackedAlphaSamples = trackedSamples.alphaSamples();
                trackedPassedSamples = trackedSamples.passedSamples();
            }

            double milliseconds = (System.nanoTime() - started) / 1_000_000.0D;
            // 调用本项目诊断器，按配置与一秒频率限制输出合成状态。
            RVP_DhCompatDiagnostics.recordComposite(
                    dhAnalysis.get().reverseZ() ? "REVERSE_Z" : "FORWARD_Z",
                    dhParameters.renderPass(), plan.remoteSelectedCount(),
                    plan.trackedLoadedCount(), plan.trackedSelectedCount(),
                    remoteAlphaSamples, remotePassedSamples,
                    trackedAlphaSamples, trackedPassedSamples, milliseconds);
            return true;
        } finally {
            // 调用像素取证，必须在 try-with-resources 完成 GL 恢复后读取 DH 附件。
            RVP_DhPixelDiagnostics.afterScope();
        }
    }

    /** 清理离屏目标、提交一层几何、复制当前 DH 深度并执行线性视深度合成。 */
    private static LayerSampleCounts compositeLayer(
            RVP_DhProjectionMath.ProjectionAnalysis layerAnalysis,
            RVP_DhProjectionMath.ProjectionAnalysis dhAnalysis,
            Matrix4f dhProjection,
            int width,
            int height,
            Runnable renderLayer,
            float occlusionBiasBlocks,
            boolean capturePassedSamples,
            String layerName) {
        OFFSCREEN_TARGET.bindAndClear();
        renderLayer.run();
        // 调用取证器扫描真实 draw FBO，验证模型是否写入预期离屏附件。
        RVP_DhPixelDiagnostics.afterGeometry(layerName, OFFSCREEN_TARGET.framebufferId());
        // 调用本项目 DH 深度复制器，每层都读取前一层已经写回的最新 DH 深度。
        DH_DEPTH_COPY.copyFrom(DH_FRAMEBUFFERS.depthSourceFramebuffer());
        // 调用取证器比对深度副本与 DH 原始附件，检查 blit 链路。
        RVP_DhPixelDiagnostics.afterDepthCopy();

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
        setMatrix(rvpInverseProjectionUniform, layerAnalysis.inverseProjection());
        setMatrix(dhInverseProjectionUniform, dhAnalysis.inverseProjection());
        setMatrix(dhProjectionUniform, dhProjection);
        setFloat(dhEmptyDepthUniform, dhAnalysis.emptyDepth());
        setFloat(dhNearDepthUniform, dhAnalysis.nearDepth());
        setFloat(dhFarPlaneUniform, dhAnalysis.farPlane());
        setFloat(occlusionBiasUniform, occlusionBiasBlocks);
        // 调用本项目独立染色开关，仅在显式启用时标识来源层，正常验收保持原色。
        setInt(diagnosticLayerColorModeUniform,
                RVP_ClientConfig.isDistantHorizonsDiagnosticLayerColorsEnabled()
                        ? ("remote".equals(layerName) ? 1 : 2) : 0);
        int alphaSamples = countLayerAlphaSamples(capturePassedSamples);
        setInt(diagnosticBypassOcclusionUniform, 0);
        int passedSamples = drawFullscreenQuadAndCountPassedSamples(capturePassedSamples);
        // 调用取证器检查当前层写回，并复查前一层像素是否仍在。
        RVP_DhPixelDiagnostics.afterCompositeLayer();
        return new LayerSampleCounts(alphaSamples, passedSamples);
    }

    /**
     * 在不写颜色与深度的前提下，仅统计离屏颜色中通过 alpha discard 的片元。
     *
     * <p>该探针把“离屏几何没有产出像素”和“DH 深度比较丢弃像素”拆成两个独立信号。</p>
     */
    private static int countLayerAlphaSamples(boolean capturePassedSamples) {
        if (!capturePassedSamples) {
            return -1;
        }
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        setInt(diagnosticBypassOcclusionUniform, 1);
        try {
            return drawFullscreenQuadAndCountPassedSamples(true);
        } finally {
            setInt(diagnosticBypassOcclusionUniform, 0);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
        }
    }

    /**
     * 绘制全屏合成，并在受限诊断帧同步读取通过 fragment discard 的样本数。
     *
     * <p>该计数只用于定位第二层是否意外覆盖全屏；未采集时返回 {@code -1}。</p>
     */
    private static int drawFullscreenQuadAndCountPassedSamples(boolean capturePassedSamples) {
        if (!capturePassedSamples) {
            drawFullscreenQuad();
            return -1;
        }
        int query = GL15.glGenQueries();
        try {
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
            try {
                drawFullscreenQuad();
            } finally {
                GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            }
            return GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT);
        } finally {
            GL15.glDeleteQueries(query);
        }
    }

    /** 单层离屏 alpha 像素数与通过 DH 遮挡后的像素数。 */
    private record LayerSampleCounts(int alphaSamples, int passedSamples) {
    }

    /** 每秒至多允许一帧执行同步 GPU 样本诊断；关闭 diagnostics 时完全不创建查询。 */
    private static boolean shouldCaptureLayerSamples() {
        if (!RVP_ClientConfig.isDistantHorizonsDiagnosticsEnabled()) {
            return false;
        }
        long now = System.nanoTime();
        if (now - lastLayerSampleCaptureNanos < LAYER_SAMPLE_CAPTURE_INTERVAL_NANOS) {
            return false;
        }
        lastLayerSampleCaptureNanos = now;
        return true;
    }

    /** 在当前世界最终目标上绘制轮廓或显式始终可见图像，不读取/写入地形深度。 */
    public static void renderLateFallback(RVP_DhVehicleFramePlan plan,
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
            if (plan.remotePlan() != null) {
                // 调用分层晚期输出，避免 remote 与 tracked 的不同投影深度混入同一离屏附件。
                renderLateLayer(destinationFramebuffer, viewport, fallbackMode,
                        () -> RVP_RemoteVehicleVisualRenderer
                                .renderPrepared(plan.remotePlan(), camera, true));
            }
            if (plan.trackedPlan() != null) {
                // 调用分层晚期输出，只提交真实载具保护副本，不重复完整 EntityRenderer 副作用。
                renderLateLayer(destinationFramebuffer, viewport, fallbackMode,
                        () -> RVP_DhTrackedVehicleProtectionRenderer
                                .renderPrepared(plan.trackedPlan()));
            }
        }
    }

    /** 把一层独立投影的颜色输出到当前世界最终目标。 */
    private static void renderLateLayer(int destinationFramebuffer,
                                        int[] viewport,
                                        DistantHorizonsFallbackMode fallbackMode,
                                        Runnable renderLayer) {
        OFFSCREEN_TARGET.bindAndClear();
        renderLayer.run();
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
        RVP_DhVehicleFrameCoordinator.markDhCompositeFailure(reason);
        RVP_DhCompatDiagnostics.warnOnce(reason, detail);
        return false;
    }
}
