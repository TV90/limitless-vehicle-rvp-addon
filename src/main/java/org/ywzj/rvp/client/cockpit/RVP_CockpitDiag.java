package org.ywzj.rvp.client.cockpit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.mixin.accessor.BaseBulletAccessor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 座舱屏幕诊断日志（2026-10-15 诊断 v2：分层可判别版）。
 *
 * <p>文件位置：{@code logs/rvp_cockpit_diag.log}（客户端 gameDir/logs 下，超过分片阈值后
 * 自动轮到 {@code rvp_cockpit_diag_<n>.log}——只新建、**从不删除**旧文件）。由客户端命令
 * {@code /cockpitdiag on|off|status|gl|trace <tick>} 控制。</p>
 *
 * <h3>为什么要分层计数（排查"屏绘戛然而止"的核心判别）</h3>
 * <pre>
 *   draws 停滞                      ⇒ 驱动层断（ClientTick 没跑 / 载具解析为 null / 节流误杀）
 *   draws 增长 + md5 停滞            ⇒ painter 执行了但没落进 RT（GL 状态：clear 后未重绑、
 *                                      深度/掩码/shader 残留 ⇒ RT 只剩清屏色）
 *   draws 增长 + md5 变化 + 屏不动    ⇒ RT 正常，采样/显示侧断（屏骨直绘被跳过、纹理绑定
 *                                      被原生渲染 mod 顶掉、着色器接管）
 *   boneFrames 归零                  ⇒ 屏骨直绘整条路径没跑（Mixin / 实例失配 / 骨不可见）
 * </pre>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CockpitDiag {

    /** 专有诊断文件（客户端 gameDir/logs 下）。 */
    public static final Path DIAG_FILE =
            net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT
                    ? net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                        .resolve("logs").resolve("rvp_cockpit_diag.log")
                    : null;

    /** 单文件超过该体积后轮换到分片文件（字节）。 */
    private static final long SHARD_BYTES = 16L * 1024 * 1024;

    /** 是否开启自动记录（默认开启：不依赖用户记得手敲 /cockpitdiag on，/cockpitdiag off 可关）。 */
    private static volatile boolean enabled = true;

    /** 逐帧 trace 剩余 tick 数（&gt;0 时每帧输出一行全量快照；命令 /cockpitdiag trace &lt;tick&gt;）。 */
    private static volatile int traceTicksLeft;

    private RVP_CockpitDiag() {}

    /** @return 自动记录是否开启。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 开启/关闭自动记录；开启时写入分隔行。 */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (enabled) {
            log("========== 诊断开启（v2 分层计数）==========");
        }
    }

    /** @return 是否处于逐帧 trace 模式。 */
    public static boolean isTracing() {
        return traceTicksLeft > 0;
    }

    /** 开启逐帧 trace：接下来 {@code ticks} 个 tick 每帧输出一行全量快照。 */
    public static void startTrace(int ticks) {
        traceTicksLeft = Math.max(0, ticks);
        log("========== 逐帧 trace 开始 ticks=" + traceTicksLeft + " ==========");
    }

    /** 每个 tick 递减 trace 计数（由 Manager 调用）。 */
    public static void tickTrace() {
        if (traceTicksLeft > 0) {
            traceTicksLeft--;
        }
    }

    // ==== 分层统计：RT 侧（painter / RT 画面）====

    /** 单屏统计：{@code draws} painter 执行次数，{@code md5} RT 画面指纹。 */
    public static final class ScreenStat {
        /** painter 被调用次数（= 进入离屏绘制的次数）。 */
        public long draws;
        /** renderToTarget 抛异常次数。 */
        public long fails;
        /** 最近一次进入绘制时的 gameTime。 */
        public long lastDrawTick = Long.MIN_VALUE;
        /** 数据层内容签名（探测数/威胁数等，证明"数据还在变"）。 */
        public String contentSig = "-";
        /** 上一次心跳时的内容签名（用于判定"内容在变、RT 画面却不变"）。 */
        public String lastContentSig = "-";
        /** 最近一次 dump 的 PNG 指纹（前 8 位）。 */
        public String md5 = "-";
        /** 指纹连续相同的轮次数（&gt;1 说明 RT 画面冻结）。 */
        public long md5Same;
        /** 最近异常摘要（全文堆栈另行节流输出）。 */
        public String lastError = "-";
    }

    /** key=屏型（radar/rwr/map）→ 统计。 */
    private static final Map<String, ScreenStat> STATS = new LinkedHashMap<>();

    /** @return 该屏型的统计对象（懒创建）。 */
    public static synchronized ScreenStat stat(String key) {
        return STATS.computeIfAbsent(key, k -> new ScreenStat());
    }

    /** @return 全部屏型统计的快照副本（供 status 命令与心跳展示，避免遍历期间被并发新增）。 */
    public static synchronized Map<String, ScreenStat> allStats() {
        return new LinkedHashMap<>(STATS);
    }

    /** 记录一次 painter 执行（进入绘制时调用）。 */
    public static synchronized void noteDraw(String key, long tick, String contentSig) {
        ScreenStat stat = stat(key);
        stat.draws++;
        stat.lastDrawTick = tick;
        stat.contentSig = contentSig;
    }

    /** 记录一次绘制异常（次数累加；完整堆栈按 30 秒节流输出到日志）。 */
    public static void noteFail(String key, Throwable throwable) {
        ScreenStat stat;
        synchronized (RVP_CockpitDiag.class) {
            stat = stat(key);
            stat.fails++;
            stat.lastError = throwable.getClass().getSimpleName() + ": "
                    + String.valueOf(throwable.getMessage());
        }
        // 完整堆栈单独节流：避免异常每帧刷爆日志（摘要已在心跳行体现）。
        logThrottled("fail_stack_" + key, 30_000L, () -> {
            java.io.StringWriter sw = new java.io.StringWriter();
            throwable.printStackTrace(new java.io.PrintWriter(sw));
            return "屏绘异常堆栈[" + key + "] " + sw;
        });
    }

    /** 记录一次 RT dump 指纹（用于判定 RT 画面是否还在变）。 */
    public static synchronized void noteDump(String key, String md5) {
        ScreenStat stat = stat(key);
        if (md5.equals(stat.md5)) {
            stat.md5Same++;
        } else {
            stat.md5 = md5;
            stat.md5Same = 0;
        }
    }

    // ==== 分层统计：显示侧（屏骨直绘，由 Mixin 每帧累加）====

    /** 单屏直绘统计：{@code frames} 本秒成功提交直绘的帧数。 */
    public static final class RenderStat {
        /** 本秒累计直绘帧数。 */
        public long frames;
        /** 本秒累计提交顶点数。 */
        public long verts;
        /** 上次采样时的帧数（用于输出每秒增量）。 */
        public long lastFrames;
    }

    /** key=屏型 → 直绘统计。 */
    private static final Map<String, RenderStat> RENDER_STATS = new LinkedHashMap<>();

    /** 屏骨每帧直绘成功后调用（Mixin 内）。 */
    public static void noteBoneDraw(String key, int vertexCount) {
        RenderStat stat = RENDER_STATS.computeIfAbsent(key, k -> new RenderStat());
        stat.frames++;
        stat.verts += vertexCount;
    }

    /** MSL 指示器菱形框累计绘制次数（{@code RVP_MslOverlay} 内调用）。 */
    private static long mslDiamondFrames;
    /** 上次结算时的菱形框次数（用于算每秒值）。 */
    private static long mslDiamondLastFrames;

    /** 每绘制一个 MSL 菱形框调用一次（MslOverlay 内）。 */
    public static void noteMslDiamond() {
        mslDiamondFrames++;
    }

    /** @return 本秒 MSL 菱形框绘制次数（读取即结算差值）。 */
    private static synchronized long mslDiamondPerSecond() {
        long perSecond = mslDiamondFrames - mslDiamondLastFrames;
        mslDiamondLastFrames = mslDiamondFrames;
        return perSecond;
    }

    /** @return 直绘统计快照副本（key=屏型）。 */
    public static Map<String, RenderStat> renderStats() {
        return new LinkedHashMap<>(RENDER_STATS);
    }

    // ==== 心跳：每秒一条，把三层计数 + GL 快照 + 相关性压缩进三行 ====

    /**
     * 每秒心跳（由 Manager 在 ClientTick 调用）：输出 RT 侧计数、显示侧计数、
     * GL 状态快照与"导弹/告警"相关性字段——四组数据对齐后可判定冻结发生在哪一层。
     *
     * @param tick    当前 gameTime（与 draws 的 lastDrawTick 同基准，可直接比差值）
     * @param vehicle 本地玩家载具（null 表示非乘载）
     */
    public static void heartbeat(long tick, AbstractVehicle vehicle) {
        if (!enabled) {
            return;
        }
        StringBuilder rt = new StringBuilder("心跳 tick=").append(tick)
                .append(" 载具=").append(vehicle == null ? "null"
                        : String.valueOf(vehicle.getVehicleId()));
        for (Map.Entry<String, ScreenStat> entry : allStats().entrySet()) {
            ScreenStat stat = entry.getValue();
            rt.append(" | ").append(entry.getKey())
                    .append(" draws=").append(stat.draws)
                    .append(" 落后=").append(stat.lastDrawTick == Long.MIN_VALUE
                            ? "-" : (tick - stat.lastDrawTick))
                    .append(" fail=").append(stat.fails)
                    .append(" md5=").append(stat.md5)
                    .append(stat.md5Same > 1 ? "(停滞×" + stat.md5Same + ")" : "")
                    .append(" 内容[").append(stat.contentSig).append("]");
        }
        log(rt.toString());

        // 自动冻结检测（一次性标记；恢复时打恢复行）——不必等用户手敲 trace：
        //   ①RT 侧冻结：内容签名在变、RT 画面指纹却连续停滞 ⇒ 画了但没落进 RT（GL 状态问题）
        //   ②驱动侧停滞：该屏绘制落后超过 40 tick ⇒ ClientTick 驱动/载具解析断了
        for (Map.Entry<String, ScreenStat> entry : allStats().entrySet()) {
            ScreenStat stat = entry.getValue();
            boolean contentChanged = !stat.contentSig.equals(stat.lastContentSig);
            boolean rtFrozen = contentChanged && stat.md5Same >= 2;
            boolean stale = stat.lastDrawTick != Long.MIN_VALUE && tick - stat.lastDrawTick > 40;
            boolean flagged = Boolean.TRUE.equals(FREEZE_FLAGGED.get(entry.getKey()));
            stat.lastContentSig = stat.contentSig;
            if ((rtFrozen || stale) && !flagged) {
                FREEZE_FLAGGED.put(entry.getKey(), Boolean.TRUE);
                log("★疑冻结[" + entry.getKey() + "]"
                        + (rtFrozen ? " RT画面停滞×" + stat.md5Same + "（内容仍在变:" + stat.contentSig + "）" : "")
                        + (stale ? " 绘制落后" + (tick - stat.lastDrawTick) + "tick" : "")
                        + " fail=" + stat.fails + " 异常=" + stat.lastError
                        + " | " + glSnapshot() + " | " + missileSnapshot(vehicle));
            } else if (!rtFrozen && !stale && flagged) {
                FREEZE_FLAGGED.put(entry.getKey(), Boolean.FALSE);
                log("☆恢复[" + entry.getKey() + "] RT 画面与绘制恢复正常");
            }
        }

        // 在场导弹数变化标记：把"屏停"和"导弹出现/消失"在时间轴上对齐。
        int missileCount = countMissiles();
        if (missileCount != lastMissileCount) {
            log("◆在场RVP导弹 " + lastMissileCount + " → " + missileCount);
            lastMissileCount = missileCount;
        }

        // 显示侧：屏骨直绘帧率（每秒增量；归零=Mixin 直绘整条路径没跑）
        StringBuilder render = new StringBuilder("显示 骨直绘/秒");
        for (Map.Entry<String, RenderStat> entry : RENDER_STATS.entrySet()) {
            RenderStat stat = entry.getValue();
            long perSec = stat.frames - stat.lastFrames;
            stat.lastFrames = stat.frames;
            render.append(" ").append(entry.getKey()).append("=").append(perSec)
                    .append("(verts=").append(stat.verts).append(")");
            // 显示侧断：RT 仍在画（draws 有历史）却这一秒一帧都没落到屏骨 ⇒ 采样/直绘路径断
            ScreenStat screenStat = STATS.get(entry.getKey());
            if (perSec == 0) {
                if (screenStat != null && screenStat.draws > 0
                        && !Boolean.TRUE.equals(RENDER_ZERO_FLAGGED.get(entry.getKey()))) {
                    RENDER_ZERO_FLAGGED.put(entry.getKey(), Boolean.TRUE);
                    log("★疑显示侧断[" + entry.getKey() + "] 本秒骨直绘=0帧（RT draws="
                            + screenStat.draws + " 仍在增长）| " + glSnapshot());
                }
            } else if (Boolean.TRUE.equals(RENDER_ZERO_FLAGGED.get(entry.getKey()))) {
                RENDER_ZERO_FLAGGED.put(entry.getKey(), Boolean.FALSE);
                log("☆恢复[" + entry.getKey() + "] 骨直绘恢复 " + perSec + " 帧/秒");
            }
        }
        log(render.toString());

        // GL 状态快照：FBO 绑定/纹理/shader/混合/深度/剔除/线宽/错误码
        log("GL " + glSnapshot());
        // 相关性：场上 RVP 导弹与 RWR 告警（"有导弹就停"的定量伴生指标）
        log("相关性 " + missileSnapshot(vehicle) + " MSL框/秒=" + mslDiamondPerSecond());
    }

    /** 冻结标记（key=屏型；已标记为 true 时不再重复输出，恢复后复位）。 */
    private static final Map<String, Boolean> FREEZE_FLAGGED = new LinkedHashMap<>();

    /** 骨直绘归零标记（key=屏型）。 */
    private static final Map<String, Boolean> RENDER_ZERO_FLAGGED = new LinkedHashMap<>();

    /** 上次心跳的在场导弹数（-1=未初始化）。 */
    private static int lastMissileCount = -1;

    /** @return 场上存活的 RVP 导弹数（与 {@link #missileSnapshot} 同源）。 */
    private static int countMissiles() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 0;
        }
        int total = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof RVP_BaseBullet bullet && bullet.isAlive()) {
                total++;
            }
        }
        return total;
    }

    /**
     * @return GL 状态快照字符串（排查离屏绘制与屏骨采样被残留状态破坏的直接依据）。
     */
    public static String glSnapshot() {
        try {
            int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            int tex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            Object shader = RenderSystem.getShader();
            String shaderName = shader == null ? "null" : shader.getClass().getSimpleName();
            boolean blend = GL11.glGetBoolean(GL11.GL_BLEND);
            boolean depth = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            boolean cull = GL11.glGetBoolean(GL11.GL_CULL_FACE);
            float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
            int err = GL11.glGetError();
            return "fbo=" + fbo + " tex0=" + tex0 + " shader=" + shaderName
                    + " blend=" + blend + " depth=" + depth + "/" + depthMask
                    + " cull=" + cull + " lineW=" + lineWidth
                    + " glErr=0x" + Integer.toHexString(err);
        } catch (Throwable throwable) {
            return "快照失败 " + throwable;
        }
    }

    /**
     * @return 场上 RVP 导弹与 RWR 告警统计（"有导弹就停"的定量伴生指标）。
     */
    public static String missileSnapshot(AbstractVehicle vehicle) {
        StringBuilder builder = new StringBuilder();
        Minecraft mc = Minecraft.getInstance();
        int total = 0;
        int indicator = 0;
        if (mc.level != null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof RVP_BaseBullet bullet && bullet.isAlive()) {
                    total++;
                    if (((BaseBulletAccessor) bullet).isShowMslIndicator()) {
                        indicator++;
                    }
                }
            }
        }
        builder.append("场上RVP导弹=").append(total).append("(指示器=").append(indicator).append(")");
        WarningReceiver receiver = vehicle == null ? null : vehicle.warningReceiver;
        if (receiver == null) {
            builder.append(" 告警=无接收器");
        } else {
            int msl = 0;
            int lock = 0;
            int search = 0;
            int other = 0;
            for (WarningReceiver.WarnTarget target : receiver.targets.values()) {
                if (target.warnType() == WarnType.MISSILE_LAUNCH) {
                    msl++;
                } else if (target.warnType() == WarnType.RADAR_LOCK) {
                    lock++;
                } else if (target.warnType() == WarnType.RADAR_SEARCH) {
                    search++;
                } else {
                    other++;
                }
            }
            builder.append(" 告警 MSL=").append(msl).append(" LOCK=").append(lock)
                    .append(" SEARCH=").append(search).append(" 其它=").append(other);
        }
        return builder.toString();
    }

    // ==== 输出 ====

    /** 追加一行诊断到专有文件（带时间戳；IO 异常静默——排查工具自身不能干扰游戏）。 */
    public static synchronized void log(String line) {
        if (DIAG_FILE == null) {
            return;
        }
        try {
            Path file = currentFile();
            if (!Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }
            String stamped = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")) + " " + line;
            try (BufferedWriter writer = Files.newBufferedWriter(file,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(stamped);
                writer.newLine();
            }
        } catch (IOException ignored) {
            // 排查工具自身故障时保持静默，避免影响游戏运行。
        }
    }

    /** 节流日志：同一 key 在 {@code intervalMs} 内最多输出一条（用于异常堆栈等大块文本）。 */
    public static void logThrottled(String key, long intervalMs, java.util.function.Supplier<String> supplier) {
        long now = System.currentTimeMillis();
        Long last = THROTTLE.putIfAbsent(key, now);
        if (last == null || now - last >= intervalMs) {
            THROTTLE.put(key, now);
            log(supplier.get());
        }
    }

    /** 节流表（key → 上次输出时间）。 */
    private static final Map<String, Long> THROTTLE = new LinkedHashMap<>();

    /**
     * @return 当前应写入的诊断文件（超过 {@link #SHARD_BYTES} 后轮换分片，只新建不删除）。
     */
    private static Path currentFile() {
        try {
            if (Files.exists(DIAG_FILE) && Files.size(DIAG_FILE) >= SHARD_BYTES) {
                int index = 1;
                Path shard;
                do {
                    shard = DIAG_FILE.resolveSibling("rvp_cockpit_diag_" + index + ".log");
                    index++;
                } while (Files.exists(shard) && Files.size(shard) >= SHARD_BYTES);
                return shard;
            }
        } catch (IOException ignored) {
            // 体积查询失败时退回主文件。
        }
        return DIAG_FILE;
    }

    /**
     * @param file 待计算指纹的文件
     * @return 文件内容 md5 的前 8 位（用于判定 RT dump 画面是否变化）；失败返回 "err"。
     */
    public static String md5Of(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4 && i < hash.length; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "err";
        }
    }
}
