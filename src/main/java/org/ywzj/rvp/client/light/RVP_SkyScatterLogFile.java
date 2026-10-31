package org.ywzj.rvp.client.light;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 天空散射追踪专用的独立日志文件：固定 UTF-8（无 BOM）、追加写入、每行立即刷新。
 *
 * <p>与 {@code RVP_TuningLog} 使用同一写入约定，但**只写自己的文件、不写 latest.log**；
 * 写盘失败只置状态位，不向渲染线程抛异常，避免影响画面。</p>
 */
final class RVP_SkyScatterLogFile {
    /** 每行前缀时间戳，保留毫秒，便于把一次爆炸的帧序列和游戏画面时序对齐。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 写入目标；由当前游戏目录推导，不在类里硬编码盘符。 */
    private final Path path;
    /** 超过该字节数时把旧文件转存一代，避免长期开启后无限增长；0 表示不轮转。 */
    private final long rotateBytes;
    /** 当前打开的写入器；null 表示尚未开启或已经关闭。 */
    private BufferedWriter writer;
    /** 写盘是否已经失败；失败后不再逐行重试，避免刷异常。 */
    private boolean failed;

    /**
     * @param path 目标文件
     * @param rotateBytes 轮转阈值（字节），0 表示不轮转
     */
    RVP_SkyScatterLogFile(Path path, long rotateBytes) {
        this.path = path;
        this.rotateBytes = rotateBytes;
    }

    /** 目标文件路径，供命令显示给玩家。 */
    Path path() { return path; }

    /** 本次会话是否发生过写入失败；命令用它提示检查磁盘或权限。 */
    boolean failed() { return failed; }

    /** 创建目录、按需轮转并写入会话头；任何异常都只置失败标记，不影响游戏。 */
    void open(String header) {
        try {
            Path absolute = path.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            rotate(absolute);
            // 调用 UTF-8 追加写入；显式字符集保证中文在任何系统区域设置下都是 UTF-8。
            writer = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            failed = false;
            write(header);
        } catch (IOException | SecurityException failure) {
            failed = true;
            close();
        }
    }

    /** 追加一行并立即刷新；换行符统一为当前系统的行分隔符，内容里的换行替换为空格。 */
    void write(String message) {
        if (writer == null || failed) { return; }
        try {
            writer.write("[" + LocalDateTime.now().format(TIME) + "] "
                    + (message == null ? "null" : message.replace('\r', ' ').replace('\n', ' ')));
            writer.newLine();
            // 立即刷新，客户端崩溃或被强杀时最近的行也不会丢。
            writer.flush();
        } catch (IOException failure) {
            failed = true;
            close();
        }
    }

    /** 关闭并刷新；重复调用安全。 */
    void close() {
        if (writer == null) { return; }
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // 关闭阶段的异常无法补救，文件内容以已经刷新过的行为准。
        } finally {
            writer = null;
        }
    }

    /** 只保留一代旧文件，日志目录不会随每次开启无限增长。 */
    private void rotate(Path absolute) throws IOException {
        if (rotateBytes <= 0 || !Files.isRegularFile(absolute) || Files.size(absolute) <= rotateBytes) { return; }
        Files.move(absolute, absolute.resolveSibling(absolute.getFileName() + ".old"),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
