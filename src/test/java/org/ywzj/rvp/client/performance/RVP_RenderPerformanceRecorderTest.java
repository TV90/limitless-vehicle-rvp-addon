package org.ywzj.rvp.client.performance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 使用显式不可用的假显卡验证无阻塞契约、嵌套、窗口归属及退出，不依赖游戏或显示器。 */
class RVP_RenderPerformanceRecorderTest {
    @Test
    void disabledPathDoesNotReadClockOrTouchGpuAndAlwaysUsesSameNoop() {
        FakeGpu gpu = new FakeGpu();
        var recorder = new RVP_RenderPerformanceRecorder(() -> { throw new AssertionError("关闭时不应读取时钟"); }, gpu);
        assertSame(RVP_RenderPerformanceRecorder.NOOP, recorder.begin(RVP_RenderStage.VEHICLE_EXPLOSION, true));
        recorder.add(RVP_RenderMetric.DRAW_CALLS, 10);
        recorder.beginFrame(Map.of()); recorder.endFrame(); recorder.stop();
        assertFalse(recorder.enabled()); assertFalse(recorder.draining());
        assertEquals(0, gpu.apiCalls);
    }

    @Test
    void asynchronousNestedRangesKeepOriginalWindowAndInclusiveCounts() {
        AtomicLong clock = new AtomicLong(); FakeGpu gpu = new FakeGpu(); MemorySink sink = new MemorySink();
        var recorder = new RVP_RenderPerformanceRecorder(clock::get, gpu);
        recorder.intervalSeconds(1); recorder.start(sink, Map.of("test", "nested"));
        recorder.beginFrame(Map.of("width", 1920));
        try (var parent = recorder.begin(RVP_RenderStage.SCREEN_COMPOSITE, true)) {
            clock.addAndGet(1_000_000);
            try (var child = recorder.begin(RVP_RenderStage.TRAIL, true)) {
                recorder.add(RVP_RenderMetric.DRAW_CALLS, 3); recorder.add(RVP_RenderMetric.COPY_BYTES, 128);
                clock.addAndGet(2_000_000);
            }
            clock.addAndGet(1_000_000);
        }
        recorder.endFrame();
        clock.set(1_100_000_000L);
        recorder.beginFrame(Map.of("width", 1920)); recorder.endFrame();
        assertEquals(0, gpu.resultReads, "未完成时不能读取QUERY_RESULT");
        assertTrue(windows(sink).isEmpty(), "原窗口尚未收齐GPU，不能把晚到数据写进新窗口");
        gpu.ready = true; clock.addAndGet(10_000_000);
        recorder.beginFrame(Map.of("width", 1280)); recorder.endFrame(); recorder.stop(); recorder.finishPending(true);
        Map<String, Object> first = windows(sink).get(0);
        var trail = row(first, RVP_RenderStage.TRAIL);
        var parent = row(first, RVP_RenderStage.SCREEN_COMPOSITE);
        assertEquals(1L, trail.get("calls")); assertEquals("available", trail.get("gpu_status"));
        assertEquals(3L, ((Map<?, ?>) trail.get("metrics")).get("draw_calls"));
        assertEquals(3L, ((Map<?, ?>) parent.get("metrics")).get("draw_calls"));
        assertTrue(ms(parent, "cpu_submit_ms") >= ms(trail, "cpu_submit_ms"));
        assertTrue(ms(parent, "gpu_ms") > ms(trail, "gpu_ms"));
        assertEquals("not_executed", row(first, RVP_RenderStage.CRT).get("gpu_status"));
        assertNull(row(first, RVP_RenderStage.CRT).get("gpu_ms"));
        assertTrue(sink.closed); assertEquals(gpu.created.size(), gpu.deleted.size());
    }

    @Test
    void unsupportedGpuKeepsCpuMeasurementsWithoutPretendingZeroGpuTime() {
        AtomicLong clock = new AtomicLong(); FakeGpu gpu = new FakeGpu(); gpu.bits = 0;
        MemorySink sink = new MemorySink(); var recorder = new RVP_RenderPerformanceRecorder(clock::get, gpu);
        recorder.start(sink, Map.of()); recorder.beginFrame(Map.of());
        try (var scope = recorder.begin(RVP_RenderStage.VEHICLE_EXPLOSION, true)) { clock.addAndGet(3_000_000); }
        recorder.endFrame(); recorder.stop();
        var row = row(windows(sink).get(0), RVP_RenderStage.VEHICLE_EXPLOSION);
        assertEquals(3, ms(row, "cpu_submit_ms")); assertEquals("unavailable", row.get("gpu_status"));
        assertNull(row.get("gpu_ms")); assertNull(row.get("gpu_ms_per_frame"));
        assertEquals(1L, row.get("gpu_missing_samples")); assertTrue(gpu.created.isEmpty());
    }

    @Test
    void hangingGpuHasBoundedQueriesAndExpiresWithoutWaitingOrDroppingCpu() {
        AtomicLong clock = new AtomicLong(); FakeGpu gpu = new FakeGpu(); MemorySink sink = new MemorySink();
        var recorder = new RVP_RenderPerformanceRecorder(clock::get, gpu);
        recorder.start(sink, Map.of()); recorder.beginFrame(Map.of());
        for (int index = 0; index < 600; index++) {
            try (var scope = recorder.begin(RVP_RenderStage.BLOOM, true)) { clock.incrementAndGet(); }
        }
        recorder.endFrame();
        assertEquals(512, gpu.created.size(), "查询对总数不得超过256，不能被大量scope绕过");
        assertEquals(0, gpu.resultReads);
        clock.set(2_100_000_000L); recorder.beginFrame(Map.of()); recorder.endFrame(); recorder.stop(); recorder.finishPending(true);
        Map<String, Object> first = windows(sink).get(0);
        assertTrue(((Number) first.get("query_budget_drops")).intValue() > 0);
        var bloom = row(first, RVP_RenderStage.BLOOM);
        assertEquals(600L, bloom.get("calls")); assertEquals(600L, bloom.get("gpu_missing_samples"));
        assertNull(bloom.get("gpu_ms")); assertEquals(0, gpu.resultReads);
        assertEquals(gpu.created.size(), gpu.deleted.size());
    }

    @Test
    void timestampsWrapAtTheirActualBitWidthAndCpuOnlyStagesStayExplicit() {
        AtomicLong clock = new AtomicLong(); FakeGpu gpu = new FakeGpu(); gpu.bits = 32; gpu.ready = true;
        gpu.timestamp = (1L << 32) - 15_000;
        MemorySink sink = new MemorySink(); var recorder = new RVP_RenderPerformanceRecorder(clock::get, gpu);
        recorder.start(sink, Map.of()); recorder.beginFrame(Map.of());
        try (var scope = recorder.begin(RVP_RenderStage.VEHICLE_EXPLOSION, true)) { clock.addAndGet(4_000_000); }
        try (var scope = recorder.begin(RVP_RenderStage.LIGHT_GEOMETRY, false)) { clock.addAndGet(1_000_000); }
        recorder.endFrame(); recorder.stop();
        var first = windows(sink).get(0);
        assertTrue(ms(row(first, RVP_RenderStage.VEHICLE_EXPLOSION), "gpu_ms") > 0);
        assertEquals("cpu_only", row(first, RVP_RenderStage.LIGHT_GEOMETRY).get("gpu_status"));
        assertNull(row(first, RVP_RenderStage.LIGHT_GEOMETRY).get("gpu_ms"));
        assertEquals(gpu.created.size(), gpu.deleted.size());
    }

    @Test
    void exceptionAndRapidRestartCloseScopesAndSeparateSessionIdentities() {
        AtomicLong clock = new AtomicLong(); FakeGpu gpu = new FakeGpu(); MemorySink first = new MemorySink();
        var recorder = new RVP_RenderPerformanceRecorder(clock::get, gpu);
        recorder.start(first, Map.of()); recorder.beginFrame(Map.of());
        var failing = recorder.begin(RVP_RenderStage.DIRCM, true).endAction(() -> { throw new IllegalStateException("draw failed"); });
        assertThrows(IllegalStateException.class, failing::close);
        failing.close(); recorder.endFrame(); recorder.stop();
        assertTrue(recorder.draining());
        MemorySink second = new MemorySink(); recorder.start(second, Map.of());
        assertTrue(first.closed); assertNotEquals(first.records.get(0).get("session"), second.records.get(0).get("session"));
        assertNull(row(windows(first).get(0), RVP_RenderStage.DIRCM).get("gpu_ms"));
        gpu.ready = true; recorder.beginFrame(Map.of()); recorder.endFrame(); recorder.stop();
        assertFalse(recorder.draining()); assertEquals(gpu.created.size(), gpu.deleted.size());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> windows(MemorySink sink) {
        return sink.records.stream().filter(record -> record.get("type").equals("window")).toList();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> window, RVP_RenderStage stage) {
        return ((List<Map<String, Object>>) window.get("stages")).stream().filter(row -> row.get("id").equals(stage.id)).findFirst().orElseThrow();
    }
    private static double ms(Map<String, Object> row, String key) { return ((Number) ((Map<?, ?>) row.get(key)).get("avg")).doubleValue(); }

    /** 测试显卡只有显式ready后才允许结果读取；违规直接使测试失败。 */
    private static final class FakeGpu implements RVP_RenderPerformanceRecorder.GpuClock {
        /** 有效时间戳位数。 */ int bits = 64;
        /** 是否允许无阻塞回读。 */ boolean ready;
        /** 所有API调用次数，关闭时必须为0。 */ int apiCalls;
        /** GPU结果实际读取次数。 */ int resultReads;
        /** 独立显卡时钟，故意不等于CPU时钟。 */ long timestamp;
        /** 创建过的全部编号。 */ final Set<Integer> created = new HashSet<>();
        /** 已删除编号，重复删除将失败。 */ final Set<Integer> deleted = new HashSet<>();
        /** 每个编号最后一次排队的时间戳。 */ final Map<Integer, Long> timestamps = new HashMap<>();
        @Override public int timestampBits() { apiCalls++; return bits; }
        @Override public int create() { apiCalls++; int id = created.size() + 1; created.add(id); return id; }
        @Override public void stamp(int query) {
            apiCalls++; timestamp += 10_000;
            timestamps.put(query, bits == 64 ? timestamp : timestamp & ((1L << bits) - 1));
        }
        @Override public boolean available(int query) { apiCalls++; return ready; }
        @Override public long result(int query) {
            apiCalls++; assertTrue(ready, "不得同步等GPU"); resultReads++; return timestamps.get(query);
        }
        @Override public void delete(int query) { apiCalls++; assertTrue(deleted.add(query), "查询不得重复释放"); }
    }
    /** 内存接收器，不需要文件线程即可核对报告的业务含义。 */
    private static final class MemorySink implements RVP_RenderPerformanceRecorder.Sink {
        /** 按发送顺序保存的完整报告。 */ final List<Map<String, Object>> records = new ArrayList<>();
        /** 收尾是否调用关闭。 */ boolean closed;
        @Override public boolean offer(Map<String, Object> record) { records.add(record); return true; }
        @Override public void close() { closed = true; }
        @Override public String status() { return "memory"; }
    }
}
