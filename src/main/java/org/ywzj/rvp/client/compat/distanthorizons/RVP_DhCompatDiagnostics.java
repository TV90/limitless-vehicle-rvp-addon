package org.ywzj.rvp.client.compat.distanthorizons;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_ClientConfig.DistantHorizonsFallbackMode;
import org.ywzj.rvp.debug.RVP_DebugFlags;

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

    /**
     * 返回 DH 兼容诊断（日志与 GPU/像素探针）是否开启。
     *
     * <p>默认关闭，只由客户端命令 {@code /rvpdebug flags dh on} 打开，避免正常游玩时
     * 每帧/每秒输出 DH 状态；关闭时所有 DH 日志与同步回读探针都零开销跳过。</p>
     */
    static boolean isDiagnosticsEnabled() {
        // 调用本项目统一调试开关，DH 日志必须由指令显式开启，不写常驻无条件日志。
        return RVP_DebugFlags.DH.isEnabled();
    }

    /** 每种不可用原因只警告一次，避免渲染事件逐帧刷日志；开关关闭时连登记都不做。 */
    public static void warnOnce(String reason, String detail) {
        if (!isDiagnosticsEnabled()) {
            return;
        }
        if (WARNED_REASONS.add(reason)) {
            LOGGER.warn("RVP DH compat: state=FALLBACK reason={} detail={}", reason, detail);
        }
    }

    /** 在诊断启用时每秒至多输出一次成功合成统计。 */
    public static void recordComposite(String depthMode, String renderPass,
                                       int remoteSelected, int trackedLoaded,
                                       int trackedSelected, int remoteAlphaSamples,
                                       int remotePassedSamples, int trackedAlphaSamples,
                                       int trackedPassedSamples, double milliseconds) {
        if (!isDiagnosticsEnabled()) {
            return;
        }
        boolean containsGpuSamples = remoteAlphaSamples >= 0 || trackedAlphaSamples >= 0;
        if (!containsGpuSamples && !canLogStatistics()) {
            return;
        }
        if (containsGpuSamples) {
            // GPU 探针自身已按一秒节流；样本帧必须直接输出，避免与普通统计节流窗口错相后只看到 -1。
            lastStatisticsNanos = System.nanoTime();
        }
        // 调用本项目无 DH 类型入口，成功日志必须显示运行期真实 API 版本。
        String apiVersion = RVP_DistantHorizonsCompatBootstrap.getApiVersion();
        LOGGER.info("RVP DH compat: state=DEPTH_AWARE_OPENGL api={} pass={} dhDepth={} "
                        + "remoteSelected={} trackedLoaded={} trackedSelected={} "
                        + "remoteAlphaSamples={} remotePassedSamples={} "
                        + "trackedAlphaSamples={} trackedPassedSamples={} compositeCpuMs={}",
                apiVersion, renderPass, depthMode,
                remoteSelected, trackedLoaded, trackedSelected,
                remoteAlphaSamples, remotePassedSamples,
                trackedAlphaSamples, trackedPassedSamples,
                String.format(java.util.Locale.ROOT, "%.3f", milliseconds));
    }

    /** 在诊断启用时每秒至多输出一次 RVP 优先显示统计。 */
    public static void recordRvpFirst(int remoteSelected, int trackedSelected) {
        if (!isDiagnosticsEnabled() || !canLogStatistics()) {
            return;
        }
        LOGGER.info("RVP DH compat: state=RVP_FIRST stage=AFTER_LEVEL "
                        + "remoteSelected={} trackedSelected={}",
                remoteSelected, trackedSelected);
    }

    /** 在诊断启用时每秒至多输出一次降级状态。 */
    public static void recordFallback(String reason, DistantHorizonsFallbackMode fallback,
                                      int remoteSelected, int trackedSelected) {
        warnOnce(reason, "fallback=" + fallback);
        if (!isDiagnosticsEnabled() || !canLogStatistics()) {
            return;
        }
        LOGGER.info("RVP DH compat: state=FALLBACK reason={} fallback={} "
                        + "remoteSelected={} trackedSelected={}",
                reason, fallback, remoteSelected, trackedSelected);
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
