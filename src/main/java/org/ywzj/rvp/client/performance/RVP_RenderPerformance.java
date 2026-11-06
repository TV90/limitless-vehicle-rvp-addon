package org.ywzj.rvp.client.performance;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.ywzj.rvp.client.light.RVP_LightingCompatibility;
import org.ywzj.rvp.client.performance.RVP_RenderPerformanceRecorder.Scope;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/** 客户端通用性能入口；默认只走一个空判断，不创建文件、线程、查询或逐次计时对象。 */
public final class RVP_RenderPerformance {
    /** 延迟建立的会话记录器；类被引用或打开F8均不会创建它。 */
    private static RVP_RenderPerformanceRecorder recorder;
    /** 最近一次会话的独立文件线程，退出时仅对它做有限刷新。 */
    private static volatile RVP_RenderPerformanceLog lastLog;
    /** 用户明确设置的统计间隔，保存参数不会开启录制。 */
    private static Double intervalOverride;
    /** 当前窗口的环境快照，分辨率等仍逐帧更新。 */
    private static long contextSampleNanos;
    /** 不需要逐帧查询的Java堆和GC读数。 */
    private static Map<String, Object> processContext = Map.of();

    private RVP_RenderPerformance() { }

    /** 正常范围入口；关闭时不读取CPU时钟或接触显卡。 */
    public static Scope scope(RVP_RenderStage stage) {
        return recorder == null ? RVP_RenderPerformanceRecorder.NOOP : recorder.begin(stage, true);
    }

    /** 纯CPU准备阶段，避免在没有显卡工作时制造一条误导性的GPU测量。 */
    public static Scope cpu(RVP_RenderStage stage) {
        return recorder == null ? RVP_RenderPerformanceRecorder.NOOP : recorder.begin(stage, false);
    }

    /** GUI可能延迟批处理；只在录制时切开批次，让GPU时间戳真正包住本效果的绘制。 */
    public static Scope gui(RVP_RenderStage stage, GuiGraphics graphics) {
        if (!enabled()) { return RVP_RenderPerformanceRecorder.NOOP; }
        graphics.flush();
        // 调用同一异步范围；结束前提交本层，不能把仅拼顶点的CPU时间当作GPU时间。
        return scope(stage).endAction(graphics::flush);
    }

    public static boolean enabled() { return recorder != null && recorder.enabled(); }
    public static boolean activeSession() { return recorder != null && (recorder.enabled() || recorder.draining()); }

    /** 计数仅在录制帧内更新；GUI面片和实际draw call分别记录。 */
    public static void add(RVP_RenderMetric metric, long value) { if (recorder != null) { recorder.add(metric, value); } }

    /** 一个真实绘制的工作量，像素和采样次数明确是上界。 */
    public static void draw(long pixelUpperBound, int raySteps) {
        if (!enabled()) { return; }
        add(RVP_RenderMetric.DRAW_CALLS, 1); add(RVP_RenderMetric.PIXELS, pixelUpperBound);
        if (raySteps > 0) { add(RVP_RenderMetric.RAY_SAMPLES, pixelUpperBound * raySteps); }
    }

    /** 统计GUI逻辑面片与实际渲染分辨率下的面积上界，不把批次合并前的面片当成draw call。 */
    public static void guiQuad(GuiGraphics graphics, int width, int height) {
        if (!enabled() || width <= 0 || height <= 0) { return; }
        var target = Minecraft.getInstance().getMainRenderTarget();
        long physicalWidth = (long) Math.ceil((double) width * target.width / Math.max(1, graphics.guiWidth()));
        long physicalHeight = (long) Math.ceil((double) height * target.height / Math.max(1, graphics.guiHeight()));
        add(RVP_RenderMetric.GUI_QUADS, 1); add(RVP_RenderMetric.PIXELS, physicalWidth * physicalHeight);
    }

    /** 一次复制按目标格式记录估算字节，不声称是硬件真实带宽。 */
    public static void copy(int width, int height, int bytesPerPixel) {
        if (!enabled()) { return; }
        add(RVP_RenderMetric.COPIES, 1); add(RVP_RenderMetric.COPY_BYTES, (long) width * height * bytesPerPixel);
    }

    /** 既有后处理链统一计时；不拆分或重排PostChain内部步骤，不引入新的注入点。 */
    public static void post(RVP_RenderStage stage, PostChain chain, float partialTick, int width, int height) {
        try (Scope ignored = scope(stage)) {
            if (enabled() && chain instanceof org.ywzj.vehicle.client.render.util.PostPassesGetter getter) {
                // 调用本体公开的既有pass列表，仅统计已登记步骤，不接管其处理逻辑。
                int passes = getter.getPasses().size();
                add(RVP_RenderMetric.POST_PASSES, passes); add(RVP_RenderMetric.PIXELS, (long) width * height * passes);
            }
            chain.process(partialTick);
        }
    }

    public static Path path() { return FMLPaths.GAMEDIR.get().resolve("logs/rvp-screen-performance.log").toAbsolutePath(); }

    /** 指令及F8共用；不持久化启用状态，每次客户端启动都保持关闭。 */
    public static String start() {
        RenderSystem.assertOnRenderThread();
        if (enabled()) { return "性能记录已经开启：" + path(); }
        if (recorder == null) { recorder = new RVP_RenderPerformanceRecorder(System::nanoTime, new TimestampClock()); }
        if (recorder.draining()) { recorder.finishPending(true); }
        lastLog = new RVP_RenderPerformanceLog(path());
        recorder.intervalSeconds(intervalSeconds());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("gpu", GL11.glGetString(GL11.GL_RENDERER)); metadata.put("vendor", GL11.glGetString(GL11.GL_VENDOR));
        metadata.put("opengl", GL11.glGetString(GL11.GL_VERSION)); metadata.put("java", System.getProperty("java.version"));
        metadata.put("file", path().toString()); metadata.put("interval_seconds", intervalSeconds());
        metadata.put("gui_timing", "recording flushes GUI boundaries; this can change batching cost");
        // 调用纯记录器开始本次会话；真正的文件IO由独立线程处理，不进入latest.log。
        recorder.start(lastLog, metadata);
        return "已开启屏幕特效性能记录：" + path();
    }

    public static String stop() {
        if (recorder != null) { recorder.stop(); }
        return "已停止新增性能采样；最后几帧显卡结果异步收尾。文件：" + path();
    }

    public static String status() {
        return (recorder == null ? "性能记录已关闭" : recorder.status()) + "；文件=" + path();
    }

    public static void mark(String message) { if (recorder != null) { recorder.mark(message); } }
    public static double intervalSeconds() { return intervalOverride == null ? 2 : intervalOverride; }
    public static OptionalDouble intervalOverride() { return intervalOverride == null ? OptionalDouble.empty() : OptionalDouble.of(intervalOverride); }
    public static void setInterval(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 1 || seconds > 30) { throw new IllegalArgumentException("统计间隔允许1～30秒。"); }
        intervalOverride = seconds;
        if (recorder != null) { recorder.intervalSeconds(seconds); }
    }
    public static void resetInterval() {
        intervalOverride = null;
        if (recorder != null) { recorder.intervalSeconds(2); }
    }

    /** Forge渲染帧开始；关闭且没有待收尾会话时连环境查询也不执行。 */
    public static void beginFrame() {
        if (!activeSession()) { return; }
        Minecraft mc = Minecraft.getInstance();
        if (!enabled()) { recorder.beginFrame(Map.of()); return; }
        long now = System.nanoTime();
        if (now - contextSampleNanos >= 1_000_000_000L || processContext.isEmpty()) {
            Runtime runtime = Runtime.getRuntime();
            long gcCount = 0, gcMillis = 0;
            for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += Math.max(0, collector.getCollectionCount()); gcMillis += Math.max(0, collector.getCollectionTime());
            }
            processContext = Map.of("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory(),
                    "gc_count_total", gcCount, "gc_time_ms_total", gcMillis);
            contextSampleNanos = now;
        }
        Map<String, Object> context = new LinkedHashMap<>(processContext);
        context.put("width", mc.getMainRenderTarget().width); context.put("height", mc.getMainRenderTarget().height);
        context.put("world", mc.level == null ? "menu" : mc.level.dimension().location().toString());
        context.put("paused", mc.isPaused()); context.put("shaderpack", RVP_LightingCompatibility.shadersActive());
        recorder.beginFrame(context);
    }

    public static void endFrame() { if (recorder != null) { recorder.endFrame(); } }

    /** JVM退出时只有限等待日志线程，不调用已销毁上下文的OpenGL。 */
    public static void flushOnExit() {
        RVP_RenderPerformanceLog log = lastLog;
        if (log != null) {
            log.offer(Map.of("type", "process_exit", "note", "unresolved GPU samples cannot be read after GL context destruction"));
            log.close();
            try { log.awaitClosed(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    /** OpenGL3.3或ARB扩展的时间戳实现，不占用其它模组的TIME_ELAPSED查询目标。 */
    static final class TimestampClock implements RVP_RenderPerformanceRecorder.GpuClock {
        @Override public int timestampBits() {
            var caps = GL.getCapabilities();
            return caps.OpenGL33 || caps.GL_ARB_timer_query ? GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) : 0;
        }
        @Override public int create() { return GL15.glGenQueries(); }
        @Override public void stamp(int query) { GL33.glQueryCounter(query, GL33.GL_TIMESTAMP); }
        @Override public boolean available(int query) { return GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
        @Override public long result(int query) { return GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT); }
        @Override public void delete(int query) { GL15.glDeleteQueries(query); }
    }
}
