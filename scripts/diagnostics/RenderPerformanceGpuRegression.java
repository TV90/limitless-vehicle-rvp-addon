package org.ywzj.rvp.client.performance;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.ywzj.rvp.client.light.VehicleExplosionGpuRegression;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 在既有正式爆炸程序上验证真实GPU计时、外部查询共存、状态不变及独立日志落盘。 */
public final class RenderPerformanceGpuRegression {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        VehicleExplosionGpuRegression.afterProductionChecks = () -> {
            try { verify(root); } catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        // 调用现有生产程序回归，在其有效OpenGL上下文内追加计时检查。
        VehicleExplosionGpuRegression.main(args);
    }

    @SuppressWarnings("unchecked")
    private static void verify(Path root) throws Exception {
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        var gpu = new RVP_RenderPerformance.TimestampClock();
        require(gpu.timestampBits() >= 30, "real timestamp support");
        Path path = root.resolve("build/render-performance-gpu-regression/rvp-screen-performance.log");
        var log = new RVP_RenderPerformanceLog(path);
        List<Map<String, Object>> records = new ArrayList<>();
        var sink = new RVP_RenderPerformanceRecorder.Sink() {
            @Override public boolean offer(Map<String, Object> record) { records.add(record); return log.offer(record); }
            @Override public void close() { log.close(); }
            @Override public String status() { return log.status(); }
        };
        var recorder = new RVP_RenderPerformanceRecorder(System::nanoTime, gpu);
        int external = GL15.glGenQueries();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, external);
        try {
            recorder.start(sink, Map.of("test", "real production vehicle explosion shader"));
            recorder.beginFrame(Map.of("width", 96, "height", 96));
            try (var parent = recorder.begin(RVP_RenderStage.SCREEN_COMPOSITE, true)) {
                try (var explosion = recorder.begin(RVP_RenderStage.VEHICLE_EXPLOSION, true)) {
                    for (int index = 0; index < 16; index++) {
                        // 调用已绑定的正式爆炸GLSL，计时范围包含真实的片元积分而非空查询。
                        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                        recorder.add(RVP_RenderMetric.DRAW_CALLS, 1);
                    }
                }
            }
            recorder.endFrame();
            require(GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) == external,
                    "nested timestamp scopes must not steal external TIME_ELAPSED target");
        } finally { GL15.glEndQuery(GL33.GL_TIME_ELAPSED); GL15.glDeleteQueries(external); }
        recorder.stop(); GL11.glFlush();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (recorder.draining() && System.nanoTime() < deadline) {
            // 仅隐藏诊断等待后续帧，不在生产逻辑使用sleep或阻塞GPU回读。
            recorder.beginFrame(Map.of()); Thread.sleep(1);
        }
        require(!recorder.draining(), "asynchronous GPU tail completes");
        require(log.awaitClosed(2000), "background log closes");
        var window = records.stream().filter(value -> value.get("type").equals("window")).findFirst().orElseThrow();
        var rows = (List<Map<String, Object>>) window.get("stages");
        var row = rows.stream().filter(value -> value.get("id").equals(RVP_RenderStage.VEHICLE_EXPLOSION.id)).findFirst().orElseThrow();
        require(row.get("gpu_status").equals("available"), "production GPU duration is available");
        double milliseconds = ((Number) ((Map<?, ?>) row.get("gpu_ms")).get("avg")).doubleValue();
        require(milliseconds > 0, "real shader elapsed GPU time");
        require(((Map<?, ?>) row.get("metrics")).get("draw_calls").equals(16L), "nested draw counter");
        require(Files.size(path) > 0, "independent file written");
        require(!Files.exists(path.resolveSibling("latest.log")), "no main-log fallback");
        require(framebuffer == GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
                && program == GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
                && vao == GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), "profiling preserves render bindings");
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "profiling OpenGL error=0");
        System.out.println("PASS asynchronous production GPU scopes, nested/external query coexistence, independent log; batch GPU ms=" + milliseconds);
    }

    /** 失败立即终止，不把未测得时间或安全降级当作计时成功。 */
    private static void require(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
