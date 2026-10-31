package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 真正读回独立文件，验证中文、操作记录和异常链都能在同一日志中查到。 */
class RVP_TuningLogTest {
    /** JUnit独立临时目录，不写入用户游戏的日志。 */
    @TempDir Path temporaryDirectory;

    @Test
    void logIsUtf8AppendOnlyAndRetainsFullExceptionCause() throws IOException {
        Path path = temporaryDirectory.resolve("logs/rvp-tuning.log");
        RVP_TuningLog log = new RVP_TuningLog(path);
        log.info("OPEN 测试调参面板");
        log.warn("INPUT_REJECT 参数越界");
        log.error("SAVE_FAILED 保存失败", new IOException("外层异常", new IllegalStateException("真实原因")));
        // 读回实际文件，不用模拟日志接口；重新创建实例也应追加而非截断。
        new RVP_TuningLog(path).info("CLOSE 测试结束");
        String text = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(text.contains("[INFO]"));
        assertTrue(text.contains("[WARN]"));
        assertTrue(text.contains("[ERROR]"));
        assertTrue(text.contains("OPEN 测试调参面板"));
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: 真实原因"));
        assertTrue(text.contains("RVP_TuningLogTest.logIsUtf8AppendOnlyAndRetainsFullExceptionCause"));
        assertTrue(text.indexOf("OPEN") < text.indexOf("CLOSE"));
    }

    @Test
    void failedLogWriteNeverBreaksTheTuningTool() throws IOException {
        Path occupied = temporaryDirectory.resolve("occupied");
        Files.writeString(occupied, "现有文件");
        RVP_TuningLog log = new RVP_TuningLog(occupied.resolve("rvp-tuning.log"));
        assertDoesNotThrow(() -> log.error("日志目录不可写", new IllegalArgumentException("测试异常")));
        assertDoesNotThrow(() -> log.info("后续操作仍可继续"));
        assertEquals("现有文件", Files.readString(occupied));
    }
}
