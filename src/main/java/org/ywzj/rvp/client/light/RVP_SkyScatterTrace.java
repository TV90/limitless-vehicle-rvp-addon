package org.ywzj.rvp.client.light;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 天空散射（skyScatter）专用排查追踪：默认关闭，用 {@code /rvplight skydebug on} 开启。
 *
 * <p>输出到游戏目录下独立的 {@code logs/rvp-sky-scatter.log}（UTF-8、无 BOM、逐行刷新），
 * **不写 latest.log**；只记录“一次爆炸从服务端事件到提交显卡”的关键节点与被剔除原因，
 * 帧摘要合节流到 2 条/秒。关闭时不产生任何写盘或格式化开销。</p>
 */
public final class RVP_SkyScatterTrace {
    /** 独立日志文件名；固定名字方便玩家一次复现后直接发送整份文件。 */
    private static final String FILE_NAME = "rvp-sky-scatter.log";
    /** 单个文件上限 4 MiB；超过后旧内容转存为 .old，只保留一代。 */
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    /** 帧摘要最小间隔，单位纳秒；500ms 即 2 条/秒，足够看清时序又不刷屏。 */
    private static final long SUMMARY_INTERVAL_NANOS = 500_000_000L;
    /** 两次摘要之间最多缓存多少条剔除原因，防止极端弹幕撑爆内存。 */
    private static final int MAX_PENDING_DROPS = 16;
    /** 是否开启追踪；会话内有效，不写配置。 */
    private static boolean enabled;
    /** 当前会话的独立日志文件；关闭时为 null。 */
    private static RVP_SkyScatterLogFile log;
    /** 上一次帧摘要的单调时钟，单位纳秒。 */
    private static long lastSummary;
    /** 上一次输出的跳帧原因，用于抑制连续重复。 */
    private static String lastSkip = "";
    /** 只输出一次的提示键，避免每帧重复。 */
    private static final Set<String> ONCE = new HashSet<>();
    /** 本摘要周期内累计的剔除原因，由 {@link #frame} 统一输出。 */
    private static final List<String> PENDING_DROPS = new ArrayList<>();

    private RVP_SkyScatterTrace() { }

    /** 是否正在追踪；调用方据此决定是否传递诊断回调。 */
    public static boolean enabled() { return enabled; }

    /** 开关追踪；开启时按当前客户端相机与画质配置写会话头，便于事后还原现场。 */
    public static void setEnabled(boolean value) {
        if (value == enabled) { return; }
        enabled = value;
        lastSkip = "";
        ONCE.clear();
        PENDING_DROPS.clear();
        if (!value) {
            if (log != null) { log.write("========== 会话结束 =========="); log.close(); log = null; }
            return;
        }
        log = new RVP_SkyScatterLogFile(resolvePath(), MAX_BYTES);
        log.open(header());
    }

    /** 命令与面板显示的状态文案，附带文件路径与写入故障提示。 */
    public static String status() {
        if (!enabled || log == null) { return "关闭"; }
        return "开启，写入 " + path()
                + (log.failed() ? "（写入失败：请检查磁盘空间或目录权限）" : "（UTF-8）");
    }

    /** 当前会话的日志文件绝对路径；未开启时返回空串，供命令提示玩家到哪里取文件。 */
    public static String path() {
        return enabled && log != null ? log.path().toAbsolutePath().toString() : "";
    }

    /**
     * 服务端视觉事件到达客户端时调用；这是整条链路的第一环。
     * 若文件里完全没有这一行，说明事件被上游屏蔽或没发到本客户端，与光源无关。
     *
     * @param type 效果类型
     * @param flash 事件是否允许闪光
     * @param position 服务端权威爆心
     * @param radius 已解析的爆炸半径
     * @param ageTicks 客户端收到时的年龄（客户端游戏刻减事件开始刻）
     */
    public static void eventReceived(ResourceLocation type, boolean flash, Vec3 position, float radius, long ageTicks) {
        if (!enabled) { return; }
        write("事件到达 类型=" + type + " flash=" + flash + " 位置=" + position(position)
                + " 半径=" + decimals(radius) + " 客户端年龄=" + ageTicks + "刻 距相机="
                + decimals(Math.sqrt(position.distanceToSqr(camera()))) + "格");
    }

    /**
     * 光源登记结果；handle=0 表示被生命周期规则拒绝，之后不会再出现任何帧日志。
     *
     * @param position 爆心
     * @param handle 管理器返回的取消句柄，0 表示未登记
     * @param tracked 登记后的跟踪条目数
     * @param skyTarget 调参表给出的天空目标强度（未乘淡出）
     * @param ageTicks 登记时的年龄
     */
    public static void lightRegistered(Vec3 position, long handle, int tracked, float skyTarget, int ageTicks) {
        if (!enabled) { return; }
        write("光源登记 handle=" + handle + (handle == 0 ? "（被拒绝：已过期、参数无效或未允许闪光）" : "")
                + " 跟踪条目=" + tracked + " 天空目标=" + decimals(skyTarget)
                + " 登记年龄=" + ageTicks + "刻 位置=" + position(position));
    }

    /** 光源被取消；自然结束与“效果预算淘汰/换世界”都会走到这里，用于区分光源是否提前消失。 */
    public static void lightClosed(long handle, String reason) {
        if (!enabled || handle == 0) { return; }
        write("光源取消 handle=" + handle + " 原因=" + reason
                + " 剩余跟踪条目=" + RVP_ScreenLightManager.INSTANCE.size());
    }

    /**
     * 按规则本就不该建灯（例如地面/命中起爆）；与“建灯后被剔除”分开记录，避免误判为预算问题。
     *
     * @param position 爆心
     * @param reason 跳过原因
     */
    public static void lightSkipped(Vec3 position, String reason) {
        if (!enabled) { return; }
        write("光源跳过（规则）位置=" + position(position) + " 原因=" + reason);
    }

    /**
     * 预算或视锥剔除一条候选；由 {@link RVP_ScreenLightBudget} 的诊断回调转发。
     * 记录进暂存区，随下一次帧摘要一起输出，避免每帧刷屏。
     */
    public static void dropped(RVP_ScreenLight light, String reason) {
        if (!enabled || PENDING_DROPS.size() >= MAX_PENDING_DROPS) { return; }
        PENDING_DROPS.add("剔除 " + light.type() + " 位置=" + position(light.position())
                + " 距相机=" + decimals(Math.sqrt(light.position().distanceToSqr(camera()))) + "格 原因=" + reason);
    }

    /**
     * 每帧摘要：跟踪数、提交数、天空灯数量与逐灯天空参数。
     * 只有真正提交给显卡的光源才会染色，因此这一行是“Java 侧是否已经把强度送出去”的判据。
     */
    public static void frame(int tracked, List<RVP_ScreenLight> submitted, String mode) {
        if (!enabled) { return; }
        long now = System.nanoTime();
        if (now - lastSummary < SUMMARY_INTERVAL_NANOS) { return; }
        lastSummary = now;
        int skyLights = 0;
        for (RVP_ScreenLight light : submitted) { if (light.skyIntensity() > 0.001F) { skyLights++; } }
        write("本帧 跟踪=" + tracked + " 提交=" + submitted.size() + " 天空灯=" + skyLights + " 模式=" + mode);
        for (RVP_ScreenLight light : submitted) {
            if (light.skyIntensity() <= 0.001F) { continue; }
            // 逐灯输出采样值；若这里是 0，问题在采样或调参，若这里非 0 而画面不亮，问题在显卡侧。
            write("  提交 " + light.type() + " 位置=" + position(light.position())
                    + " sky=" + decimals(light.skyIntensity()) + " spread=" + decimals(light.skySpread())
                    + " 照亮半径=" + decimals(light.radius()) + " 光晕半径=" + decimals(light.haloRadius())
                    + (light.emission() == null ? " 核心=无"
                    : " 核心半径=" + decimals(light.emission().coreRadius())
                    + " 核心亮度=" + decimals(light.emission().coreIntensity())));
        }
        for (String drop : PENDING_DROPS) { write("  " + drop); }
        PENDING_DROPS.clear();
    }

    /** 本帧被整体跳过；原因变化时才输出一行，并顺带清空暂存的剔除原因。 */
    public static void skip(String reason) {
        if (!enabled) { return; }
        if (!PENDING_DROPS.isEmpty()) {
            for (String drop : PENDING_DROPS) { write("  " + drop); }
            PENDING_DROPS.clear();
        }
        if (reason == null || reason.equals(lastSkip)) { return; }
        lastSkip = reason;
        write("本帧跳过：" + reason);
    }

    /** 只输出一次的提示，例如着色器 uniform 是否真的被描述 JSON 声明。 */
    public static void once(String key, String message) {
        if (!enabled || !ONCE.add(key)) { return; }
        write(message);
    }

    /** 追加一行；未开启或文件不可写时静默丢弃，绝不影响渲染。 */
    private static void write(String message) {
        if (!enabled || log == null) { return; }
        log.write(message);
    }

    /** 会话头：时间、文件位置、相机、跟踪条目与全部相关画质/调参值，便于事后还原现场。 */
    private static String header() {
        return "========== [RVP][Sky] 会话开始 " + LocalDateTime.now() + " =========="
                + " | 相机=" + position(camera())
                + " 跟踪条目=" + RVP_ScreenLightManager.INSTANCE.size()
                + " | 画质 screenLight=" + RVP_LightingConfig.screenEnabled()
                + " bloom=" + RVP_LightingConfig.bloomEnabled()
                + " intensity=" + decimals(RVP_LightingConfig.intensity())
                + " maxDistance=" + decimals(RVP_LightingConfig.maxDistance())
                + " skyDistance=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.global, RVP_LightRuntimeTuning.Parameter.skyDistance))
                + " maxLights=" + RVP_LightingConfig.maxLights()
                + " | 调参 skyPeakBrightness=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.skyPeakBrightness))
                + " skyHoldTicks=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.skyHoldTicks))
                + " skyFadeTicks=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.skyFadeTicks))
                + " skyContract=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.skyContract))
                + " haloShrink=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.haloShrink))
                + " peakHold=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.haloPeakHoldTicks))
                + " fadeOut=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.haloFadeOutTicks))
                + " worldHaloIntensity=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.worldHaloIntensity))
                + " brightness=" + decimals(RVP_LightRuntimeTuning.get(
                        RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.brightness));
    }

    /** 日志固定落在游戏目录的 logs 下，不写 latest.log，也不需要用户改配置。 */
    private static Path resolvePath() {
        Minecraft minecraft = Minecraft.getInstance();
        File directory = minecraft.gameDirectory == null ? new File(".") : minecraft.gameDirectory;
        return new File(new File(directory, "logs"), FILE_NAME).toPath();
    }

    /** 调用客户端主相机位置；早期或缺少渲染器时退回原点，日志不因取相机失败而中断。 */
    private static Vec3 camera() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameRenderer == null) { return Vec3.ZERO; }
        return minecraft.gameRenderer.getMainCamera().getPosition();
    }

    /** 位置按一位小数输出，便于对照爆心与相机距离。 */
    private static String position(Vec3 value) {
        return "(" + decimals(value.x) + ", " + decimals(value.y) + ", " + decimals(value.z) + ")";
    }

    /** 统一十进制格式，避免不同系统语言产生逗号小数点。 */
    private static String decimals(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
