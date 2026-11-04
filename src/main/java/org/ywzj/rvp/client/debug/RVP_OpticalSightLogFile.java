package org.ywzj.rvp.client.debug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** 吊舱实机取证专用文件；默认不打开，UTF-8 追加写入，失败不影响鼠标和渲染。 */
final class RVP_OpticalSightLogFile {
    /** 完整目标路径，由当前游戏目录提供。 */
    private final Path path;
    /** 写入器为空即关闭；构造和关闭状态均不创建文件。 */
    private BufferedWriter writer;
    /** 最近一次写入错误，供指令显示；不向 latest.log 反复刷异常。 */
    private String failure;
    /** 本次开启的单调时钟起点，避免系统时间调整打乱事件顺序。 */
    private long startedNanos;
    /** 每行递增的编号，用于关联同刻多次输入和多个渲染帧。 */
    private long sequence;

    RVP_OpticalSightLogFile(Path path) { this.path = path; }

    /** 只读当前开关，不触碰磁盘。 */
    boolean isOpen() { return writer != null; }

    /** 返回错误详情；null 表示没有写入错误。 */
    String failure() { return failure; }

    /** 显式开启才创建文件；再次开启不会覆盖先前实机记录。 */
    void open(String metadata) {
        if (writer != null) return;
        failure = null;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            startedNanos = System.nanoTime();
            sequence = 0L;
            // 调用统一行写入，会话头明确单位和格式，后续脚本可以按编号与单调时间重放。
            write("SESSION_BEGIN", "utc=" + Instant.now() + " schema=1 " + metadata);
            flush();
        } catch (IOException | SecurityException error) {
            fail(error);
        }
    }

    /** 缓冲追加一行；关闭时立即返回，不会因一次误调用生成空日志。 */
    void write(String stage, String details) {
        if (writer == null) return;
        try {
            writer.write("seq=" + (++sequence) + " elapsed_ns=" + (System.nanoTime() - startedNanos)
                    + " stage=" + stage + " " + details.replace('\r', ' ').replace('\n', ' '));
            writer.newLine();
        } catch (IOException error) {
            fail(error);
        }
    }

    /** 由客户端刻定期刷新，不在每个鼠标样本后同步刷盘。 */
    void flush() {
        if (writer == null) return;
        try { writer.flush(); } catch (IOException error) { fail(error); }
    }

    /** 关闭与退出世界时写入终点并刷盘；重复关闭安全。 */
    void close(String reason) {
        if (writer == null) return;
        write("SESSION_END", "utc=" + Instant.now() + " reason=" + reason);
        if (writer == null) return;
        try { writer.close(); } catch (IOException error) {
            failure = error.toString();
        } finally { writer = null; }
    }

    /** 文件异常只关闭本诊断会话，不能打断玩家输入或相机事件。 */
    private void fail(Exception error) {
        failure = error.toString();
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) { }
            writer = null;
        }
    }
}
