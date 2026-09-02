package org.ywzj.rvp.client.compat.distanthorizons;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.config.RVP_ClientConfig.DistantHorizonsFallbackMode;

import java.util.HashSet;
import java.util.Set;

/** DH 兼容的一次性降级原因与每秒受限统计。 */
public final class RVP_DhCompatDiagnostics {
    /** 兼容诊断日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 已输出的一次性失败原因集合。 */
    private static final Set<String> WARNED_REASONS = new HashSet<>();
    /** 上次周期统计输出的纳秒时间。 */
    private static long lastStatisticsNanos;

    private RVP_DhCompatDiagnostics() {
    }

    /** 每种不可用原因只警告一次，避免渲染事件逐帧刷日志。 */
    public static void warnOnce(String reason, String detail) {
        if (WARNED_REASONS.add(reason)) {
            LOGGER.warn("RVP DH compat: state=FALLBACK reason={} detail={}", reason, detail);
        }
    }

    /** 在诊断启用时每秒至多输出一次成功合成统计。 */
    public static void recordComposite(String depthMode, String renderPass,
                                       int selected, double milliseconds) {
        if (!RVP_ClientConfig.isDistantHorizonsDiagnosticsEnabled() || !canLogStatistics()) {
            return;
        }
        // 调用本项目无 DH 类型入口，成功日志必须显示运行期真实 API 版本。
        String apiVersion = RVP_DistantHorizonsCompatBootstrap.getApiVersion();
        LOGGER.info("RVP DH compat: state=DEPTH_AWARE_OPENGL api={} pass={} dhDepth={} "
                        + "selected={} compositeMs={}",
                apiVersion, renderPass, depthMode,
                selected, String.format(java.util.Locale.ROOT, "%.3f", milliseconds));
    }

    /** 在诊断启用时每秒至多输出一次降级状态。 */
    public static void recordFallback(String reason, DistantHorizonsFallbackMode fallback, int selected) {
        warnOnce(reason, "fallback=" + fallback);
        if (!RVP_ClientConfig.isDistantHorizonsDiagnosticsEnabled() || !canLogStatistics()) {
            return;
        }
        LOGGER.info("RVP DH compat: state=FALLBACK reason={} fallback={} selected={}",
                reason, fallback, selected);
    }

    /** 检查一秒统计节流窗口并推进时间戳。 */
    private static boolean canLogStatistics() {
        long now = System.nanoTime();
        if (now - lastStatisticsNanos < 1_000_000_000L) {
            return false;
        }
        lastStatisticsNanos = now;
        return true;
    }
}
