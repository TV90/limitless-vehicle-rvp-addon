package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 骨骼隐藏（状态机/距离 LOD）调试输出。
 * <p>
 * 与 RVP 其它调试（{@link RVP_DualPulseDebug} 等）同款模式：
 * 默认关闭，通过 {@code /rvpdebug bonehide on|off|status|clear} 控制，
 * 日志写入独立的 {@code logs/rvp_bonehide_debug.log}，不污染 latest.log。
 */
public final class RVP_BoneHideDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("rvp_bonehide_debug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_BoneHideDebug() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static Path getLogPath() {
        return LOG_PATH;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        if (enabled) {
            clearLog();
        }
        appendFileLog("toggle enabled=" + enabled);
    }

    public static void clearLog() {
        try {
            Files.deleteIfExists(LOG_PATH);
        } catch (IOException e) {
            LOGGER.error("[RVP][BoneHideDebug] Failed to clear {}", LOG_PATH, e);
        }
    }

    /** 仅在开关开启时写入独立日志文件。 */
    public static void log(String message) {
        if (!ENABLED.get()) {
            return;
        }
        appendFileLog(message);
    }

    private static void appendFileLog(String message) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP][BoneHideDebug] Failed to append debug log to {}", LOG_PATH, e);
        }
    }
}
