package org.ywzj.rvp.client.compat.distanthorizons;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect.RVP_DhTrackedVehicleFramePlan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 临时实机取证：沿同一帧的实际像素检查离屏、DH 附件及世界目标，不改变原绘制路径。 */
final class RVP_DhPixelDiagnostics {
    /** 日志统一使用 RVP DH pixel 前缀，便于从 debug.log 提取同帧证据。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 全图 alpha 扫描最短间隔；关闭 diagnostics 时不执行任何 GL 查询。 */
    private static final long INTERVAL_NANOS = 5_000_000_000L;
    /** 防止异常窗口尺寸触发超大同步回读，最多覆盖常见 4K 画面。 */
    private static final long MAX_PIXELS = 8_847_360L;
    /** 上次启动像素取证的时间。 */
    private static long lastCaptureNanos;
    /** 单调递增的取证帧编号，与各阶段日志关联。 */
    private static long sequence;
    /** 当前取证帧，延续到 AFTER_LEVEL 后释放。 */
    private static Frame frame;
    /** 是否正在执行 RVP 合成；防止 shader 被其它路径调用时重复记录。 */
    private static boolean compositing;

    private RVP_DhPixelDiagnostics() {
    }

    /** 在分层样本帧中至多每五秒启动一次像素取证。 */
    static void begin(boolean sampled, int width, int height, int dhFramebuffer,
                      int dhColorTexture, int dhDepthTexture) {
        frame = null;
        compositing = false;
        long now = System.nanoTime();
        // 调用本项目配置入口，只允许显式 diagnostics 会话执行同步回读。
        if (!sampled || !RVP_ClientConfig.isDistantHorizonsDiagnosticsEnabled()
                || now - lastCaptureNanos < INTERVAL_NANOS
                || (long) width * height > MAX_PIXELS) {
            return;
        }
        lastCaptureNanos = now;
        frame = new Frame(++sequence, width, height, dhFramebuffer);
        compositing = true;
        LOGGER.info("RVP DH pixel: frame={} stage=BEGIN size={}x{} dhFbo={} dhColor={} dhDepth={}",
                frame.id, width, height, dhFramebuffer, dhColorTexture, dhDepthTexture);
        logErrors("ENTRY_PREEXISTING");
    }

    /** 在既有五秒取证帧记录目标身份与取光差异，不增加逐帧查询或修改模型状态。 */
    static void trackedLighting(RVP_DhTrackedVehicleFramePlan plan) {
        if (!compositing || frame == null) {
            return;
        }
        inspect(() -> {
            // 调用保护层计划访问器，只检查本帧实际入选的目标，不按车型 ID 分支。
            for (RVP_DhTrackedVehicleFramePlan.Candidate candidate : plan.selected()) {
                var vehicle = candidate.vehicle();
                BlockPos origin = vehicle.blockPosition();
                // 调用本体实体的光照探针入口，记录正常渲染实际使用的主碰撞盒中心。
                BlockPos probe = BlockPos.containing(vehicle.getLightProbePosition(plan.partialTick()));
                // 复算旧版原点光照仅用于对照；已加载区块才读取，避免诊断触发空区块访问。
                boolean originLoaded = plan.level().hasChunkAt(origin);
                int originLight = originLoaded ? LevelRenderer.getLightColor(plan.level(), origin) : -1;
                // 调用原版正常实体取光入口，与已经冻结并应用摧毁暗化的候选光照一起记录。
                int nativeLight = Minecraft.getInstance().getEntityRenderDispatcher()
                        .getPackedLightCoords(vehicle, plan.partialTick());
                LOGGER.info("RVP DH pixel: frame={} stage=TRACKED_LIGHT entity={} display={} "
                                + "origin={} probe={} originLoaded={} probeLoaded={} originLight={} "
                                + "nativeLight={} selectedLight={} block={} sky={} destroyed={}",
                        frame.id, vehicle.getId(), vehicle.getDisplayId(), origin, probe, originLoaded,
                        plan.level().hasChunkAt(probe), originLight, nativeLight, candidate.packedLight(),
                        LightTexture.block(candidate.packedLight()), LightTexture.sky(candidate.packedLight()),
                        vehicle.isDestroyed());
            }
        });
    }

    /** 扫描实际 draw FBO 的 alpha，并挑选最大 alpha 的一个像素作为本层追踪点。 */
    static void afterGeometry(String layer, int expectedFramebuffer) {
        if (!compositing || frame == null) {
            return;
        }
        inspect(() -> {
            frame.layer = layer;
            logErrors("GEOMETRY_PRE_READ");
            int actual = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            try (ReadScope ignored = new ReadScope(actual)) {
                int color = attachment(GL30.GL_COLOR_ATTACHMENT0);
                int depth = attachment(GL30.GL_DEPTH_ATTACHMENT);
                // 全图先清零，避免覆盖范围不足的回读把旧内存当成非透明模型像素。
                ByteBuffer pixels = MemoryUtil.memCalloc(frame.width * frame.height * 4);
                try {
                    GL11.glReadPixels(0, 0, frame.width, frame.height,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                    int count = 0;
                    int maxAlpha = 0;
                    int selected = -1;
                    for (int offset = 0; offset < pixels.capacity(); offset += 4) {
                        int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
                        if (alpha != 0) {
                            count++;
                        }
                        if (alpha > maxAlpha) {
                            maxAlpha = alpha;
                            selected = offset / 4;
                        }
                    }
                    LOGGER.info("RVP DH pixel: frame={} stage=GEOMETRY layer={} expectedFbo={} "
                                    + "actualFbo={} color={} depth={} nonzeroAlpha={} vao={}",
                            frame.id, layer, expectedFramebuffer, actual, color, depth, count,
                            GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
                    logErrors("GEOMETRY_READBACK");
                    if (selected >= 0) {
                        Point point = new Point(layer, selected % frame.width, selected / frame.width);
                        frame.points.add(point);
                        logPoint("SOURCE", actual, point, true);
                        logPoint("DH_BEFORE_" + layer, frame.dhFramebuffer, point, true);
                    }
                } finally {
                    MemoryUtil.memFree(pixels);
                }
            }
        });
    }

    /** 在 blit 后回读深度副本，验证其值与当前 DH 附件一致。 */
    static void afterDepthCopy() {
        if (!compositing || frame == null || frame.points.isEmpty()) {
            return;
        }
        inspect(() -> {
            Point point = frame.points.get(frame.points.size() - 1);
            logErrors("DEPTH_COPY_PRE_READ");
            if (point.layer.equals(frame.layer)) {
                logPoint("DEPTH_COPY_" + frame.layer,
                        GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), point, false);
            }
        });
    }

    /** ShaderInstance.apply 返回后记录 GPU 真实状态，保持 BufferUploader 原有绑定/绘制顺序。 */
    private static void afterShaderApply(ShaderInstance shader) {
        if (!compositing || frame == null) {
            return;
        }
        inspect(() -> {
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            StringBuilder samplers = new StringBuilder();
            try {
                for (String name : List.of("RvpColor", "RvpDepth", "DhDepthCopy")) {
                    int unit = uniformInt(shader.getId(), name);
                    if (unit < 0 || unit >= 32) {
                        samplers.append(name).append("=invalid;");
                        continue;
                    }
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                    samplers.append(name).append('@').append(unit).append('=')
                            .append(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)).append(';');
                }
            } finally {
                GL13.glActiveTexture(active);
            }
            byte[] mask = new byte[4];
            ByteBuffer maskBuffer = MemoryUtil.memAlloc(4);
            try {
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, maskBuffer);
                maskBuffer.get(mask);
            } finally {
                MemoryUtil.memFree(maskBuffer);
            }
            LOGGER.info("RVP DH pixel: frame={} stage=SHADER_APPLIED layer={} "
                            + "expectedProgram={} actualProgram={} drawFbo={} vao={} samplers={} "
                            + "colorMask={} depthMask={} depthTest={} depthFunc={} bypass={}",
                    frame.id, frame.layer, shader.getId(), program,
                    GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), samplers,
                    Arrays.toString(mask), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                    GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
                    uniformInt(shader.getId(), "DiagnosticBypassOcclusion"));
            logErrors("SHADER_APPLIED");
        });
    }

    /** 在每一层写回后检查全部追踪点，包含 remote 点是否被 tracked 意外擦除。 */
    static void afterCompositeLayer() {
        if (compositing && frame != null) {
            inspect(() -> logDhPoints("DH_AFTER_" + frame.layer));
        }
    }

    /** 外层 GL 作用域已经恢复后再次检查同一组 DH 附件。 */
    static void afterScope() {
        if (compositing && frame != null) {
            inspect(() -> logDhPoints("DH_AFTER_SCOPE"));
        }
        compositing = false;
    }

    /** DH 公开 cleanup 前事件或 Forge AFTER_LEVEL 调用，检查世界目标的同坐标颜色。 */
    static void afterWorldStage(String stage, boolean finish) {
        if (frame == null || compositing) {
            return;
        }
        try {
            inspect(() -> {
                int actual = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int main = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
                logDhPoints(stage + "_DH");
                for (Point point : frame.points) {
                    logPoint(stage + "_CURRENT", actual, point, true);
                    if (main != actual) {
                        logPoint(stage + "_MAIN", main, point, true);
                    }
                }
            });
        } finally {
            if (finish) {
                frame = null;
            }
        }
    }

    /** 记录全部 DH 追踪点；不把非零样本或一次调用返回当成写回成功。 */
    private static void logDhPoints(String stage) {
        for (Point point : frame.points) {
            logPoint(stage, frame.dhFramebuffer, point, true);
        }
    }

    /** 读取一个附件像素；所有坐标均为 OpenGL 左下原点，适用于同尺寸实机回归。 */
    private static void logPoint(String stage, int framebuffer, Point point, boolean withColor) {
        try (ReadScope ignored = new ReadScope(framebuffer)) {
            ByteBuffer pixel = MemoryUtil.memAlloc(4);
            try {
                String rgba = "none";
                if (withColor) {
                    // 颜色哨兵只用于提示未写入的可能性；深度 NaN 才作为本点无效的明确标志。
                    pixel.putInt(0, 0x5a5a5a5a);
                    GL11.glReadPixels(point.x, point.y, 1, 1,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                    rgba = Byte.toUnsignedInt(pixel.get(0)) + "," + Byte.toUnsignedInt(pixel.get(1))
                            + "," + Byte.toUnsignedInt(pixel.get(2)) + "," + Byte.toUnsignedInt(pixel.get(3));
                }
                // 合法深度应在 [0,1]；越界/失败回读保留 NaN，禁止把旧内存解释为真实深度。
                pixel.putFloat(0, Float.NaN);
                GL11.glReadPixels(point.x, point.y, 1, 1,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
                float rawDepth = pixel.getFloat(0);
                LOGGER.info("RVP DH pixel: frame={} stage={} point={} xy={},{} fbo={} "
                                + "color={} depth={} rgba={} rawDepth={} depthReadValid={}",
                        frame.id, stage, point.layer, point.x, point.y, framebuffer,
                        withColor ? attachment(GL30.GL_COLOR_ATTACHMENT0) : 0,
                        attachment(GL30.GL_DEPTH_ATTACHMENT), rgba, rawDepth,
                        Float.isFinite(rawDepth) && rawDepth >= 0.0F && rawDepth <= 1.0F);
                logErrors(stage + "_READBACK");
            } finally {
                MemoryUtil.memFree(pixel);
            }
        }
    }

    /** 读取当前 read FBO 的附件对象；默认窗口目标没有纹理附件。 */
    private static int attachment(int attachment) {
        return GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == 0 ? 0
                : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    /** 读取 GPU uniform 的实际整数值，缺失时返回 -1。 */
    private static int uniformInt(int program, String name) {
        int location = GL20.glGetUniformLocation(program, name);
        return location < 0 ? -1 : GL20.glGetUniformi(program, location);
    }

    /** 显式记录积累的 GL 错误；错误可能来自上一检查点之后，不能把失败回读当作有效像素。 */
    private static void logErrors(String stage) {
        for (int index = 0; index < 8; index++) {
            int error = GL11.glGetError();
            if (error == GL11.GL_NO_ERROR) {
                return;
            }
            LOGGER.warn("RVP DH pixel: frame={} stage={} glError=0x{}",
                    frame.id, stage, Integer.toHexString(error));
        }
    }

    /** 取证失败只停止本帧取证，不把诊断异常变成正式渲染回退。 */
    private static void inspect(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("RVP DH pixel: capture failed", exception);
            frame = null;
            compositing = false;
        }
    }

    /** 通过继承公开 shader 类观察 apply 完成点，不添加 Mixin 或改变顶点提交逻辑。 */
    static final class ObservedShader extends ShaderInstance {
        ObservedShader(ResourceProvider provider, ResourceLocation name) throws IOException {
            super(provider, name, DefaultVertexFormat.POSITION);
        }

        @Override
        public void apply() {
            super.apply();
            // 调用本项目只读取证，在原有 BufferUploader.drawWithShader 真正 draw 之前记录状态。
            afterShaderApply(this);
        }
    }

    /** 本次取证帧的尺寸、目标与各层选定像素。 */
    private static final class Frame {
        /** 日志关联编号。 */
        private final long id;
        /** DH 与 RVP 目标宽度。 */
        private final int width;
        /** DH 与 RVP 目标高度。 */
        private final int height;
        /** 借用 DH 颜色/深度的 RVP FBO。 */
        private final int dhFramebuffer;
        /** 每层至多一个真实非透明像素。 */
        private final List<Point> points = new ArrayList<>();
        /** 当前正在取证的层。 */
        private String layer = "none";

        private Frame(long id, int width, int height, int dhFramebuffer) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.dhFramebuffer = dhFramebuffer;
        }
    }

    /** 同一帧持续追踪的像素；layer 为来源层，x/y 为左下原点像素坐标。 */
    private record Point(String layer, int x, int y) {
    }

    /** 临时读取附件时隔离 read FBO 与 pack 状态，不触碰 draw FBO 和引擎状态缓存。 */
    private static final class ReadScope implements AutoCloseable {
        /** 原 read FBO。 */
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        /** 原像素打包缓冲；CPU 回读前必须临时解绑。 */
        private final int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        /** 需要归零/标准化的打包参数。 */
        private final int[] parameters = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
                GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SWAP_BYTES,
                GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES};
        /** 各打包参数的原值。 */
        private final int[] values = new int[parameters.length];

        private ReadScope(int framebuffer) {
            for (int index = 0; index < parameters.length; index++) {
                values[index] = GL11.glGetInteger(parameters[index]);
                GL11.glPixelStorei(parameters[index], index == 0 ? 1 : 0);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
        }

        @Override
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            for (int index = 0; index < parameters.length; index++) {
                GL11.glPixelStorei(parameters[index], values[index]);
            }
        }
    }
}
