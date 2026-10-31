package org.ywzj.rvp.client.tuning;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 调参工具的独立UTF-8日志；只在实际操作或异常发生时写入，不逐帧、不逐拖动采样刷盘。 */
public final class RVP_TuningLog {
    /** 日志文件本身无法写入时的一次性回退提示，避免记录故障导致游戏崩溃。 */
    private static final Logger FALLBACK = LogUtils.getLogger();
    /** 本机时间戳，保留毫秒方便对照一次输入或预览。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 当前实例唯一负责的文件，运行时由当前gameDir推导。 */
    private final Path path;
    /** 本轮写盘故障是否已经向主日志报告，避免每次操作都重复堆栈。 */
    private boolean reportedWriteFailure;

    public RVP_TuningLog(Path path) { this.path = path; }
    public Path path() { return path; }

    /** 正常启动、布局、有效改值和操作成功。 */
    public void info(String message) { append("INFO", message, null); }
    /** 可恢复的输入拒绝或配置提示；不把用户尚未输完的数值当成客户端崩溃。 */
    public void warn(String message) { append("WARN", message, null); }
    /** 失败的操作与完整异常链，供直接阅读此文件排查。 */
    public void error(String message, Throwable failure) { append("ERROR", message, failure); }

    /** 每次操作完成后关闭并刷新文件；客户端异常退出时也不会丢失最近的错误。 */
    private synchronized void append(String level, String message, Throwable failure) {
        try {
            Path absolute = path.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            try (var writer = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String cleanMessage = message == null ? "null" : message.replace('\r', ' ').replace('\n', ' ');
                writer.write("[" + LocalDateTime.now().format(TIME) + "][" + level + "]["
                        + Thread.currentThread().getName() + "] " + cleanMessage);
                writer.newLine();
                if (failure != null) {
                    StringWriter trace = new StringWriter();
                    failure.printStackTrace(new PrintWriter(trace));
                    writer.write(trace.toString());
                }
            }
            reportedWriteFailure = false;
        } catch (IOException | SecurityException failureToWrite) {
            if (!reportedWriteFailure) {
                reportedWriteFailure = true;
                FALLBACK.error("[RVP Tuning] 独立调参日志无法写入：{}；工具继续运行。", path, failureToWrite);
            }
        }
    }
}
