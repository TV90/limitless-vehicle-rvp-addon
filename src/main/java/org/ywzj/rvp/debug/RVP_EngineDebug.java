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
 * 引擎骨骼部件（直击累计/衰减/档位跨越）调试输出。
 * <p>
 * 与 {@link RVP_BoneHideDebug} 等同款模式：默认关闭，通过
 * {@code /rvpdebug engine on|off|status|clear} 控制，日志写入独立的
 * {@code logs/rvp_engine_debug.log}，不污染 latest.log。</p>
 *
 * <p>记录内容（{@code RVP_VehicleHitboxFactorManager#accumulateEngineDamage} 每次直击一条）：
 * 命中的引擎骨、入账伤害（分轨后实际到骨值）、窗口累计、衰减速率、受损/瘫痪阈值、
 * 模块存活状态、跨档结果——供"累计是裸伤还是实际伤害"的实机对账。</p>
 */
public final class RVP_EngineDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("rvp_engine_debug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_EngineDebug() {}

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
            LOGGER.error("[RVP][EngineDebug] Failed to clear {}", LOG_PATH, e);
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
            LOGGER.error("[RVP][EngineDebug] Failed to append debug log to {}", LOG_PATH, e);
        }
    }
}
