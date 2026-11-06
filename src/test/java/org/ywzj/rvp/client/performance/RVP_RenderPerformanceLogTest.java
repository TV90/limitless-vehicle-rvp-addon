package org.ywzj.rvp.client.performance;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实后台文件线程的UTF-8、故障隔离、快速重启和轮转回归，只写测试临时目录。 */
class RVP_RenderPerformanceLogTest {
    /** 当前用例自己的文件系统目录。 */
    @TempDir Path directory;

    @Test
    void writesIndependentUtf8JsonLinesAndKeepsNullGpuMeasurements() throws Exception {
        Path path = directory.resolve("logs/rvp-screen-performance.log");
        assertFalse(Files.exists(path));
        var log = new RVP_RenderPerformanceLog(path);
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("type", "test"); row.put("title", "程序大爆炸"); row.put("gpu_ms", null);
        assertTrue(log.offer(row)); log.close(); assertTrue(log.awaitClosed(2000));
        var parsed = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals("程序大爆炸", parsed.get("title").getAsString()); assertTrue(parsed.get("gpu_ms").isJsonNull());
        assertFalse(Files.exists(path.resolveSibling("latest.log")));
        assertTrue(log.status().contains("丢弃=0"));
    }

    @Test
    void writeFailureIsQueryableAndDoesNotCreateFallbackMainLog() throws Exception {
        Path blocked = directory.resolve("blocked"); Files.writeString(blocked, "a file cannot be a directory");
        var log = new RVP_RenderPerformanceLog(blocked.resolve("rvp-screen-performance.log"));
        log.offer(Map.of("type", "test")); log.close(); assertTrue(log.awaitClosed(2000));
        assertTrue(log.status().contains("文件错误="));
        assertFalse(Files.exists(directory.resolve("latest.log")));
    }

    @Test
    void concurrentSessionTailsAppendWholeRecordsAndRotateOnlyTheirOwnFile() throws Exception {
        Path path = directory.resolve("rvp-screen-performance.log");
        var first = new RVP_RenderPerformanceLog(path, 4096);
        var second = new RVP_RenderPerformanceLog(path, 4096);
        for (int index = 0; index < 20; index++) {
            first.offer(Map.of("session", "first", "index", index, "padding", "甲".repeat(20)));
            second.offer(Map.of("session", "second", "index", index, "padding", "乙".repeat(20)));
        }
        first.close(); second.close(); assertTrue(first.awaitClosed(2000)); assertTrue(second.awaitClosed(2000));
        assertTrue(Files.isRegularFile(path)); assertTrue(Files.isRegularFile(path.resolveSibling(path.getFileName() + ".1")));
        for (Path file : Files.list(directory).toList()) {
            for (String line : Files.readAllLines(file)) { assertTrue(JsonParser.parseString(line).isJsonObject()); }
        }
    }
}
