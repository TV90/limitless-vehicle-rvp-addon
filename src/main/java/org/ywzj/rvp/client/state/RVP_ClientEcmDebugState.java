package org.ywzj.rvp.client.state;

import net.minecraftforge.fml.loading.FMLPaths;
import org.ywzj.rvp.network.S2CEcmDebug;
import org.ywzj.rvp.debug.RVP_DebugFlags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 客户端主动ECM 调试快照状态（F10 调试覆盖层读取）。
 * 由 {@link org.ywzj.rvp.network.S2CEcmDebug} 每 20 tick 从服务端更新。
 * 同时把快照与干扰事件追加写入游戏目录下的 {@code logs/rvp_ecm_debug.log}，方便复制排查（单客户端亦可）。
 */
public final class RVP_ClientEcmDebugState {

    private static volatile S2CEcmDebug latest;
    /** 最近一次收到快照的系统时间（毫秒），用于判断是否过期。 */
    private static long lastUpdateMs = -1;
    /** 最近一次写文件时间，节流到 ~1s 一次，避免刷屏。 */
    private static long lastWriteMs = -1;
    /** 上一帧本人被干扰导弹数，用于检测新增/解除干扰事件。 */
    private static int prevMyJammed = 0;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private RVP_ClientEcmDebugState() {}

    public static void update(S2CEcmDebug msg) {
        latest = msg;
        lastUpdateMs = System.currentTimeMillis();
        // 干扰事件日志：本人被干扰导弹数变化时，立即写一行事件（便于复现复制）
        if (msg.myMissilesJammed() > prevMyJammed) {
            appendEvent("事件: 你发射的导弹被主动ECM干扰 +" + (msg.myMissilesJammed() - prevMyJammed)
                    + " (当前共 " + msg.myMissilesJammed() + " 枚)");
        } else if (msg.myMissilesJammed() < prevMyJammed) {
            appendEvent("事件: 干扰解除 -" + (prevMyJammed - msg.myMissilesJammed())
                    + " (当前共 " + msg.myMissilesJammed() + " 枚)");
        }
        prevMyJammed = msg.myMissilesJammed();
        // 节流写快照：约每 1000ms 一次
        long now = System.currentTimeMillis();
        if (now - lastWriteMs >= 1000L) {
            lastWriteMs = now;
            appendToFile(msg);
        }
    }

    public static S2CEcmDebug get() {
        return latest;
    }

    public static boolean hasData() {
        return latest != null && (System.currentTimeMillis() - lastUpdateMs) < 15000L;
    }

    public static void clear() {
        latest = null;
    }

    /** 供客户端 ECM 相关处理（如 RWR 伪造锁定注入）追加调试行到 rvp_ecm_debug.log。 */
    public static void appendLog(String line) {
        appendEvent(line);
    }

    /** 客户端 ECM 调试开关（默认开，排查完置 false 即可关闭探针等输出）。 */
    public static boolean isDebugOn() {
        // ECM 调试探针开关，由 /rvpdebug flags ecm 控制
        return RVP_DebugFlags.ECM.isEnabled();
    }

    /** 把单行事件追加到日志文件。 */
    private static void appendEvent(String line) {
        appendRaw("[" + LocalTime.now().format(TIME_FMT) + "] " + line + "\n");
    }

    /** 把快照追加到游戏目录 logs/rvp_ecm_debug.log。 */
    private static void appendToFile(S2CEcmDebug d) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(LocalTime.now().format(TIME_FMT)).append("] ");
        sb.append("自身装备=").append(d.ownEquipped() ? "是" : "否");
        sb.append(" 释放=").append(d.ownActive() ? "是" : "否");
        sb.append(" 剩余=").append(d.ownActiveRemain() / 20).append("s");
        sb.append(" 冷却=").append(d.ownCooldownRemain() / 20).append("s");
        sb.append(" | 本人被干扰导弹=").append(d.myMissilesJammed());
        sb.append(" | 附近放ECM载具=").append(d.entries().size());
        sb.append('\n');
        for (S2CEcmDebug.Entry e : d.entries()) {
            sb.append("    #").append(e.vehicleId)
                    .append(" dist=").append(e.dist).append("m")
                    .append(" ammoR=").append(e.ammoRadius)
                    .append(" vehR=").append(e.vehicleRadius)
                    .append(" 剩余=").append(e.activeRemain / 20).append("s\n");
        }
        appendRaw(sb.toString());
    }

    /** 底层追加写入（含目录创建与超阈值清空），供快照与事件共用。 */
    private static void appendRaw(String text) {
        // 客户端 ECM 调试文件日志统一开关：/rvpdebug flags ecm
        if (!RVP_DebugFlags.ECM.isEnabled()) {
            return;
        }
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("rvp_ecm_debug.log");
            if (Files.exists(file) && Files.size(file) > 256 * 1024L) {
                Files.delete(file);
            }
            Files.write(file, text.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 调试日志写失败不影响游戏
        }
    }
}
