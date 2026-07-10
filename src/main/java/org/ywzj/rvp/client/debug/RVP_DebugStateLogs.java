package org.ywzj.rvp.client.debug;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class RVP_DebugStateLogs {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Path CCIP_LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("ccipdebug.log");
    private static final Path IR_HMS_LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("irhms.log");

    private static boolean ccipEnabled;
    private static boolean irHmsEnabled;
    private static String lastCcipLine = "";
    private static String lastIrHmsLine = "";

    private RVP_DebugStateLogs() {}

    public static synchronized boolean isCcipEnabled() {
        return ccipEnabled;
    }

    public static synchronized void setCcipEnabled(boolean enabled) {
        ccipEnabled = enabled;
    }

    public static synchronized boolean isIrHmsEnabled() {
        return irHmsEnabled;
    }

    public static synchronized void setIrHmsEnabled(boolean enabled) {
        irHmsEnabled = enabled;
    }

    public static Path getCcipLogPath() {
        return CCIP_LOG_PATH;
    }

    public static Path getIrHmsLogPath() {
        return IR_HMS_LOG_PATH;
    }

    public static synchronized void clearCcipLog() {
        clear(CCIP_LOG_PATH);
        lastCcipLine = "";
    }

    public static synchronized void clearIrHmsLog() {
        clear(IR_HMS_LOG_PATH);
        lastIrHmsLine = "";
    }

    public static synchronized void logCcip(String message) {
        if (!ccipEnabled) {
            return;
        }
        if (message != null && message.equals(lastCcipLine)) {
            return;
        }
        lastCcipLine = message == null ? "" : message;
        append(CCIP_LOG_PATH, message);
    }

    public static synchronized void logIrHms(String message) {
        if (!irHmsEnabled) {
            return;
        }
        if (message != null && message.equals(lastIrHmsLine)) {
            return;
        }
        lastIrHmsLine = message == null ? "" : message;
        append(IR_HMS_LOG_PATH, message);
    }

    private static void clear(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.error("Failed to clear debug log {}", path, e);
        }
    }

    private static void append(Path path, String message) {
        try {
            Files.createDirectories(path.getParent());
            String line = "[" + LocalDateTime.now().format(TS) + "] " + (message == null ? "" : message) + System.lineSeparator();
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("Failed to append debug log {}", path, e);
        }
    }
}
