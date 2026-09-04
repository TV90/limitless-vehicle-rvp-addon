package org.ywzj.rvp.client.debug;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.ywzj.rvp.debug.RVP_DebugFlags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class RVP_DebugStateLogs {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Path IR_HMS_LOG_PATH = FMLPaths.GAMEDIR.get().resolve("irhms.log");

    private RVP_DebugStateLogs() {}

    public static void logIrHms(String message) {
        // 头盔显示（IR 锁定状态）日志，开关：/rvpdebug flags hmd
        if (!RVP_DebugFlags.HMD.isEnabled()) {
            return;
        }
        append(IR_HMS_LOG_PATH, message);
    }

    private static void append(Path path, String message) {
        String line = "[" + LocalDateTime.now().format(TS_FORMAT) + "] " + message + System.lineSeparator();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            LOGGER.error("[RVP][DebugStateLogs] Failed to append debug log to {}", path, e);
        }
    }
}
