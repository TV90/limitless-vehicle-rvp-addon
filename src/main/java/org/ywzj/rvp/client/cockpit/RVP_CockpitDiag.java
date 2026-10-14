package org.ywzj.rvp.client.cockpit;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 座舱屏幕诊断日志（2026-10-14）：独立于 latest.log 的专有排查文件。
 *
 * <p>文件位置：{@code logs/rvp_cockpit_diag.log}（客户端 gameDir/logs 下）。
 * 由客户端命令 {@code /cockpitdiag on|off|status} 控制开关；开启后
 * {@link RVP_CockpitScreenManager} 每 20 tick（1 秒）自动写入一条驱动状态，
 * 每次离屏回灌写入成功/失败与像素采样。文件只追加、跨会话保留，便于排查。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CockpitDiag {

    /** 专有诊断文件（客户端 gameDir/logs 下）。 */
    public static final Path DIAG_FILE =
            net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT
                    ? net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                        .resolve("logs").resolve("rvp_cockpit_diag.log")
                    : null;

    /** 是否开启自动记录（默认开启：不依赖用户记得手敲 /cockpitdiag on，/cockpitdiag off 可关）。 */
    private static volatile boolean enabled = true;

    private RVP_CockpitDiag() {}

    /** @return 自动记录是否开启。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 开启/关闭自动记录；开启时写入分隔行。 */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (enabled) {
            log("========== 诊断开启 ==========");
        }
    }

    /** 追加一行诊断到专有文件（带时间戳；IO 异常静默——排查工具自身不能干扰游戏）。 */
    public static synchronized void log(String line) {
        if (DIAG_FILE == null) {
            return;
        }
        try {
            if (!Files.exists(DIAG_FILE.getParent())) {
                Files.createDirectories(DIAG_FILE.getParent());
            }
            String stamped = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")) + " " + line;
            try (BufferedWriter writer = Files.newBufferedWriter(DIAG_FILE,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(stamped);
                writer.newLine();
            }
        } catch (IOException ignored) {
            // 排查工具自身故障时保持静默，避免影响游戏运行。
        }
    }
}
