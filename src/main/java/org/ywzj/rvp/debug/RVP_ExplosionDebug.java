package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 爆炸诊断日志（**默认关闭**，写独立文件，绝不进 {@code latest.log}）。
 *
 * <p>开关：{@code /rvpdebug flags explosion on|off|status}，状态定义见
 * {@link RVP_DebugFlags#EXPLOSION}。日志落盘 {@code logs/rvp_explosion_debug.log}，
 * 只在开关打开时写；关闭状态下连字符串拼接都不会发生（调用方先判 {@link #enabled()}）。</p>
 *
 * <p>只有"写文件失败"这种异常才走模组 logger（会进 latest.log），因为那是需要用户知道的
 * 真实故障，不是诊断噪音。</p>
 */
public final class RVP_ExplosionDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get()
            .resolve("logs")
            .resolve("rvp_explosion_debug.log");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private RVP_ExplosionDebug() {
    }

    /** 开关是否打开；调用方必须先判它再拼字符串。 */
    public static boolean enabled() {
        return RVP_DebugFlags.EXPLOSION.isEnabled();
    }

    /**
     * 记录一次爆炸的墙后遮挡快照结果。
     *
     * @param scanned 本次扫描到的实体数
     * @param factors 被判为"有遮挡"的 实体ID → 倍率
     */
    public static void wallOcclusion(Level level, Vec3 center, double radius, int scanned,
                                     Map<Integer, Float> factors) {
        if (!enabled()) {
            return;
        }
        append("wall center=" + format(center)
                + " r=" + String.format("%.1f", radius)
                + " scanned=" + scanned
                + " occluded=" + factors.size()
                + " factors=" + factors);
    }

    /** 记录爆炸波及命中包的补发情况（击毁时的展板仲裁调试用）。 */
    public static void blastHitIndicator(int vehicleId, float healthBefore, float healthAfter,
                                         float reported, boolean destroyed) {
        if (!enabled()) {
            return;
        }
        append("hitboard vehicle=" + vehicleId + " before=" + String.format("%.1f", healthBefore)
                + " after=" + String.format("%.1f", healthAfter)
                + " reported=" + String.format("%.1f", reported)
                + " destroyed=" + destroyed);
    }

    /** 记录近炸直伤的墙后衰减（这条路径绕过 VehicleExplosion）。 */
    public static void proximityAttenuation(int targetId, float before, float after, float factor) {
        if (!enabled()) {
            return;
        }
        append("proximity target=" + targetId + " damage " + String.format("%.1f", before)
                + " -> " + String.format("%.1f", after) + " factor=" + factor);
    }

    public static Path logPath() {
        return LOG_PATH;
    }

    private static String format(Vec3 v) {
        return "(" + String.format("%.2f", v.x) + ", " + String.format("%.2f", v.y)
                + ", " + String.format("%.2f", v.z) + ")";
    }

    /** 线程安全地追加一行；仅开关打开时才会被调用。 */
    private static synchronized void append(String message) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 写文件失败属于真实故障，进 latest.log 便于用户报告
            LOGGER.error("[RVP][爆炸诊断] 无法写入 {}", LOG_PATH, e);
        }
    }
}
