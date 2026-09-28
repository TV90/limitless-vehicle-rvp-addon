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
 * 炮管部件（两档累计/受损档/炸膛）调试输出。
 * <p>
 * 与 {@link RVP_EngineDebug} 同款模式：默认关闭，通过
 * {@code /rvpdebug barrel on|off|status|clear} 控制，日志写入独立的
 * {@code logs/rvp_barrel_debug.log}，不污染 latest.log。</p>
 *
 * <p>记录内容：累计入账/跨档（受损档/彻底损坏）、射击 gate 三选一 roll 结果
 * （正常散大/哑火/炸膛）、炸膛升级流程每一步（清受损态 → destroyModule 返回值 →
 * 失效广播 → 特效发布）——供"炸膛无特效/无音效"类问题的实机定位。</p>
 */
public final class RVP_BarrelDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("rvp_barrel_debug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_BarrelDebug() {}

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
            LOGGER.error("[RVP][BarrelDebug] Failed to clear {}", LOG_PATH, e);
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
            LOGGER.error("[RVP][BarrelDebug] Failed to append debug log to {}", LOG_PATH, e);
        }
    }
}
