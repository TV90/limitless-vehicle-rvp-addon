package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Server-authoritative, opt-in trace for the complete {@link RVP_BaseBullet} lifecycle.
 *
 * <p>The monitor is disabled by default. Use
 * {@code /rvpdebug projectilelife on|off|status|language|dump|clear}. While enabled it writes one
 * {@link Event#TICK} snapshot per server tick plus event records for impacts, fuzes, damage,
 * explosions, submunitions and entity removal. The independent log avoids flooding
 * {@code latest.log}.</p>
 *
 * <p>All filtering is based on projectile runtime data and entity type. No weapon resource ID
 * is used to select behavior.</p>
 *
 * 针对完整{@link RVP_BaseBullet}生命周期的服务器权威型、选择加入式跟踪。
 * 默认情况下，监视器处于关闭状态。可以通过
 * {@code /rvpdebug projectilelife on|off|status|language|dump|clear}命令来开启或关闭它，或查看状态、切换语言、转储数据或清除记录。
 * 启用后，它会为每个服务器TICK周期记录一个
 * {@link Event#TICK}快照，并记录撞击、引信触发、伤害、
 * 爆炸、子弹药以及实体移除等事件。独立日志系统避免了
 * 对{@code latest.log}文件的过度写入。
 * 所有过滤操作均基于投射物的运行时数据和实体类型，不使用武器资源ID来确定行为。
 */
public final class RVP_ProjectileLifecycleDebug {
    /** 将文件写入失败等监测器自身异常输出到模组日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 全局启用开关；默认关闭，避免正常游戏期间产生逐 tick 磁盘写入。 */
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    /** 日志默认输出中文；可由 projectilelife language 指令在运行时切换。 */
    private static volatile OutputLanguage outputLanguage = OutputLanguage.ZH_CN;
    /** 与普通 latest.log 分离的弹体生命周期日志路径。 */
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get()
            .resolve("logs")
            .resolve("rvp_projectile_lifecycle_debug.log");
    /** 每条日志的墙钟时间格式，用于和服务器 gameTime 对照分析卡顿。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 单枚弹体在内存快照中最多保留的日志行数。 */
    private static final int MAX_HISTORY_LINES_PER_PROJECTILE = 400;
    /** 常规情况下最多保留的弹体 trace 数量。 */
    private static final int MAX_RETAINED_PROJECTILES = 256;
    /** watchdog 的扫描间隔，单位为服务器游戏刻。 */
    private static final int WATCHDOG_SCAN_INTERVAL_TICKS = 20;
    /** 没有收到弹体 tick 达到该时长后判定为停滞，单位为服务器游戏刻。 */
    private static final int STALL_THRESHOLD_TICKS = 40;
    /** stalled trace 超过容量时允许被淘汰的最短停滞时长。 */
    private static final int STALLED_TRACE_RETENTION_TICKS = 1200;

    /**
     * 以实体 UUID 为主键保存生命周期；不能使用 entityId，因为 entityId 会被服务器复用。
     */
    private static final Map<UUID, ProjectileTrace> TRACES = new ConcurrentHashMap<>();
    /** 上一次 watchdog 扫描使用的服务器 gameTime。 */
    private static long lastWatchdogScanGameTime = Long.MIN_VALUE;
    /** 活动 trace 超限告警是否已经写入，防止每次扫描重复刷日志。 */
    private static boolean activeTraceLimitWarningLogged;

    /** 英文字段名与中文显示名；只改变日志文本，不改变内部事件和状态模型。 */
    private static final String[][] ZH_FIELD_NAMES = {
            {"gameTime", "游戏时间"}, {"entityId", "实体ID"}, {"uuid", "UUID"},
            {"weapon", "武器"}, {"kind", "弹体类型"}, {"class", "实体类"},
            {"tick", "原生Tick"}, {"event", "事件"}, {"update", "更新序号"},
            {"alive", "存活"}, {"removed", "已移除"}, {"life", "剩余寿命"},
            {"position", "位置"}, {"velocity", "速度向量"}, {"speed", "速度"},
            {"rotation", "旋转"}, {"targetEntity", "目标实体"}, {"targetPos", "目标位置"},
            {"lastGuidancePos", "上次制导位置"}, {"phase", "制导相位"},
            {"source", "制导源"}, {"stage", "制导阶段"}, {"radarOn", "雷达开启"},
            {"radarCatch", "雷达截获"}, {"radarLostTicks", "雷达丢失Tick"},
            {"motorBurning", "发动机燃烧"}, {"secondPulseStart", "二脉冲开始Tick"},
            {"superTickMicros", "父类Tick耗时微秒"}, {"rvpTickMicros", "RVP自身Tick耗时微秒"},
            {"totalTickMicros", "Tick总耗时微秒"}, {"changes", "变化"},
            {"traveledDistance", "已飞行距离"}, {"remainingDistance", "剩余距离"},
            {"actualElapsedSeconds", "实际经过时间秒"},
            {"averageSpeedBlocksPerSecond", "已飞行段平均速度格每秒"},
            {"reason", "原因"}, {"action", "处理"}, {"owner", "所有者"},
            {"shooterVehicle", "发射载具"}, {"loaded", "区块已加载"},
            {"entityTicking", "允许实体Tick"}, {"chunk", "区块"},
            {"lastChunk", "上次区块"}, {"currentChunk", "当前区块"},
            {"lastTickGameTime", "上次Tick游戏时间"}, {"elapsedSinceTick", "距上次Tick"},
            {"elapsed", "已过Tick"}, {"lastTick", "上次原生Tick"},
            {"lastPosition", "上次位置"}, {"removalRequested", "已请求移除"},
            {"trackingEnded", "追踪已结束"}, {"stalled", "Tick已停滞"},
            {"leftLevel", "已离开世界"}, {"stalledFor", "停滞Tick"},
            {"plannedChunkCount", "规划区块数"}, {"requestedChunkCount", "已请求区块数"},
            {"readyChunkCount", "就绪区块数"}, {"firstUnreadyChunk", "首个未就绪区块"},
            {"firstUnreadyState", "首个未就绪状态"}, {"chunkWaitTicks", "区块等待Tick"},
            {"budgetExhausted", "预算耗尽"}, {"projectedPathTruncated", "预测路径截断"},
            {"submunitionDepth", "子弹药层级"}, {"signatureSize", "信号尺寸"},
            {"airburstDistance", "空爆距离"}, {"coldLaunchTicks", "冷发射Tick"},
            {"parent", "父弹体"}, {"child", "子弹体"}, {"childWeapon", "子弹体武器"},
            {"childDepth", "子弹体层级"}, {"aliveBeforeRemove", "移除前存活"},
            {"enabled", "已启用"}, {"logPath", "日志路径"}, {"traceCount", "追踪数量"},
            {"player", "玩家"}, {"dimension", "维度"}, {"entity", "实体"},
            {"removalReason", "移除原因"}
    };

    /** 纯静态工具类，禁止实例化。 */
    private RVP_ProjectileLifecycleDebug() {}

    /** 生命周期日志输出语言。 */
    public enum OutputLanguage {
        ZH_CN,
        EN_US
    }

    /** 生命周期日志中可出现的事件类型。 */
    public enum Event {
        /** 已根据武器数据完成弹体初始化。 */
        INITIALIZED,
        /** 弹体即将加入世界，生成参数已经最终确定。 */
        SPAWN_READY,
        /** 一次仍处于活动状态的服务端弹体 tick 已完成。 */
        TICK,
        /** 弹体运行配置缺失。 */
        CONFIG_MISSING,
        /** 发射者或发射载具校验失败。 */
        SHOOTER_INVALID,
        /** 本 Tick 运动路径尚未全部进入 entity-ticking，弹体开始原地等待。 */
        WAITING_FOR_CHUNK,
        /** 等待中的运动路径已经就绪，弹体恢复飞行。 */
        CHUNK_READY_RESUME,
        /** 区块等待达到安全上限，弹体无爆炸地丢弃。 */
        CHUNK_WAIT_TIMEOUT,
        /** 子弹药触发、释放或生成。 */
        SUBMUNITION_TRIGGER,
        /** 弹体命中方块。 */
        BLOCK_HIT,
        /** 弹体穿透墙体。 */
        WALL_PENETRATION,
        /** 弹体命中实体。 */
        ENTITY_HIT,
        /** 弹体穿透生物实体。 */
        LIVING_PENETRATION,
        /** 弹体发生跳弹。 */
        BOUNCE,
        /** 弹体结算直接伤害。 */
        DIRECT_DAMAGE,
        /** 引信触发或进入引信处理路径。 */
        FUSE,
        /** 空爆条件被抑制。 */
        AIRBURST_SUPPRESSED,
        /** 弹体执行爆炸结算。 */
        EXPLOSION,
        /** 撒布器释放载荷。 */
        DISPENSER,
        /** 弹体寿命耗尽。 */
        LIFE_END,
        /** 代码经过 Entity.remove(...) 发起移除请求。 */
        REMOVED,
        /** watchdog 发现弹体长期未收到 tick。 */
        TICK_STALLED,
        /** stalled 或 trackingEnded 弹体重新收到 tick。 */
        TICK_RESUMED,
        /**
         * EntityLeaveLevelEvent 的 RemovalReason 为空：实体停止追踪/停止 ticking，但不是终止状态。
         */
        TRACKING_END,
        /** EntityLeaveLevelEvent 已携带 RemovalReason：trace 到达终止状态。 */
        LEFT_LEVEL
    }

    /** @return 生命周期监测器当前是否启用。 */
    public static boolean isEnabled() {
        return ENABLED.get();
    }

    /** @return 当前生命周期日志输出语言。 */
    public static OutputLanguage getOutputLanguage() {
        return outputLanguage;
    }

    /** 切换后只影响后续文件输出和新生成的快照；默认值为中文。 */
    public static void setOutputLanguage(OutputLanguage language) {
        outputLanguage = language == null ? OutputLanguage.ZH_CN : language;
        appendFileLog(outputLanguage == OutputLanguage.ZH_CN
                ? "监测器 输出语言=中文"
                : "monitor outputLanguage=English");
    }

    /** @return 当前语言是否为中文，供 projectilelife 指令反馈复用。 */
    public static boolean isChineseOutput() {
        return outputLanguage == OutputLanguage.ZH_CN;
    }

    /** @return 适合指令状态显示的稳定语言代码。 */
    public static String getOutputLanguageCode() {
        return outputLanguage == OutputLanguage.ZH_CN ? "zh_cn" : "en_us";
    }

    /** @return 独立生命周期日志的绝对或游戏目录解析路径。 */
    public static Path getLogPath() {
        return LOG_PATH;
    }

    /**
     * 开启或关闭监测器。开启时开始一轮全新监测并清空内存 trace；关闭时保留 trace 供 dump。
     *
     * @param enabled 是否启用监测
     */
    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        if (enabled) {
            // 新一轮监测不能继承旧 UUID、watchdog 时间或容量告警状态。
            TRACES.clear();
            lastWatchdogScanGameTime = Long.MIN_VALUE;
            activeTraceLimitWarningLogged = false;
        }
        // 无论开启还是关闭都留下控制事件，便于判断日志为何停止。
        appendFileLog("monitor enabled=" + enabled);
    }

    /** 清空内存 trace 和独立日志文件，同时重置 watchdog 扫描状态。 */
    public static void clearLog() {
        TRACES.clear();
        lastWatchdogScanGameTime = Long.MIN_VALUE;
        activeTraceLimitWarningLogged = false;
        try {
            // 删除后由下一条日志按需重新创建，不保留旧文件尾部。
            Files.deleteIfExists(LOG_PATH);
        } catch (IOException e) {
            if (isChineseOutput()) {
                LOGGER.error("[RVP][弹体生命周期调试] 无法清空 {}", LOG_PATH, e);
            } else {
                LOGGER.error("[RVP][ProjectileLifecycleDebug] Failed to clear {}", LOG_PATH, e);
            }
        }
    }

    /**
     * 在 {@code initFromWeapon} 完成后记录完整初始化状态，并以 UUID 建立新 trace。
     *
     * @param projectile 已完成武器数据初始化的弹体
     */
    public static void noteInitialized(RVP_BaseBullet projectile) {
        if (!shouldTrace(projectile)) {
            return;
        }
        // 初始化事件必须覆盖相同 UUID 的异常旧记录，确保新生命周期从这里开始。
        ProjectileTrace trace = new ProjectileTrace(projectile);
        TRACES.put(projectile.getUUID(), trace);
        // 新增 trace 后立即执行容量治理，防止调试器无限占用内存。
        trimRetainedTraces(projectile.level().getGameTime());
        // 保存弹体来源、初速度、寿命和关键运行参数。
        record(trace, projectile, Event.INITIALIZED,
                "owner=" + formatEntity(projectile.getOwner())
                        + " shooterVehicle=" + formatEntity(projectile.getShooterVehicle())
                        + " position=" + formatVec(projectile.position())
                        + " velocity=" + formatVec(projectile.getDeltaMovement())
                        + " speed=" + decimal(projectile.getCurrentSpeed())
                        + " life=" + projectile.life
                        + " submunitionDepth=" + projectile.getSubmunitionDepth()
                        + " signatureSize=" + decimal(projectile.getSignatureSize())
                        + " airburstDistance=" + projectile.getProgrammedAirburstDistance());
    }

    /**
     * 在弹体加入世界前记录最终生成状态，并在子弹药场景中补充父子关系。
     *
     * @param projectile 即将加入世界的弹体
     * @param parent 子弹药的父弹体；普通发射为 null
     */
    public static void noteSpawnReady(RVP_BaseBullet projectile, @Nullable RVP_BaseBullet parent) {
        // 使用惰性 details，关闭监测时不构造长字符串。
        noteEvent(projectile, Event.SPAWN_READY,
                () -> "parent=" + formatEntity(parent)
                        + " owner=" + formatEntity(projectile.getOwner())
                        + " shooterVehicle=" + formatEntity(projectile.getShooterVehicle())
                        + " position=" + formatVec(projectile.position())
                        + " velocity=" + formatVec(projectile.getDeltaMovement())
                        + " rotation=(" + decimal(projectile.getXRot()) + ","
                        + decimal(projectile.getYRot()) + ")"
                        + " targetEntity=" + formatEntity(projectile.getTargetEntity())
                        + " targetPos=" + formatVec(projectile.getTargetPos())
                        + " coldLaunchTicks=" + projectile.getColdLaunchTimeTick()
                        + " submunitionDepth=" + projectile.getSubmunitionDepth());
        if (parent != null) {
            // 同时在父弹体 trace 中记录 child UUID/实体，便于跨 trace 追踪释放链。
            noteEvent(parent, Event.SUBMUNITION_TRIGGER,
                    () -> "action=spawn_child child=" + formatEntity(projectile)
                            + " childWeapon=" + safeId(projectile.getWeaponId())
                            + " childDepth=" + projectile.getSubmunitionDepth());
        }
    }

    /**
     * 记录一次服务端弹体 tick 后的活动状态；若 trace 曾停滞，则先记录恢复转换。
     * 已移除弹体由 {@link #noteLeftLevel(ServerLevel, RVP_BaseBullet)} 负责终止记录，避免终止事件后再写 TICK。
     *
     * @param projectile 本 tick 刚执行完成的弹体
     * @param superTickNanos {@code super.tick()} 消耗的纳秒数
     * @param rvpTickNanos RVP 自身 Tick（不含 {@code super.tick()} 和日志写入）消耗的纳秒数
     * @param totalTickNanos 前两项之和
     */
    public static void noteTick(
            RVP_BaseBullet projectile,
            long superTickNanos,
            long rvpTickNanos,
            long totalTickNanos) {
        if (!shouldTrace(projectile) || projectile.isRemoved()) {
            return;
        }
        // 通过 UUID 获取 trace，杜绝 entityId 复用导致的生命周期串线。
        ProjectileTrace trace = trace(projectile);
        String phase = projectile.getGuidancePhaseState().phase().name();
        String source = projectile.getActiveSourceType() == null
                ? "<none>"
                : projectile.getActiveSourceType().name();
        String stage = projectile.getActiveStageName() == null
                ? "<none>"
                : projectile.getActiveStageName();
        String target = formatEntity(projectile.getTargetEntity());
        boolean radarOn = projectile.isAutonomousSeekerOn();
        boolean radarCatch = projectile.hasAutonomousSeekerCatch();
        boolean motorBurning = projectile.isMotorBurningNow();

        // 先读取旧 stalled/trackingEnded 状态，再原子更新最后 tick 快照。
        ResumeSnapshot resumed = trace.updateTickSnapshot(projectile);
        FlightMetrics flightMetrics = trace.flightMetrics();
        double remainingDistance = resolveRemainingDistance(projectile);
        if (resumed != null) {
            // 恢复事件必须先于本次普通 TICK，日志顺序才能表达状态转换。
            recordTrace(trace, projectile.level().getGameTime(), Event.TICK_RESUMED,
                    "stalledFor=" + resumed.stalledFor()
                            + " trackingEnded=" + resumed.trackingEnded()
                            + " lastChunk=" + formatChunk(resumed.lastChunk())
                            + " currentChunk=" + formatChunk(new ChunkPos(projectile.blockPosition())));
        }

        // 对比上次制导状态，只在 changes 中突出真正变化的字段。
        String changes = trace.describeChanges(phase, source, stage, target, radarOn, radarCatch, motorBurning);
        String details = "update=" + projectile.getUpdateCount()
                + " alive=" + projectile.isAlive()
                + " removed=" + projectile.isRemoved()
                + " life=" + projectile.life
                + " position=" + formatVec(projectile.position())
                + " velocity=" + formatVec(projectile.getDeltaMovement())
                + " speed=" + decimal(projectile.getCurrentSpeed())
                + " traveledDistance=" + decimal(flightMetrics.traveledDistance())
                + " remainingDistance=" + decimalOrNull(remainingDistance)
                + " actualElapsedSeconds=" + decimal(flightMetrics.actualElapsedSeconds())
                + " averageSpeedBlocksPerSecond=" + decimal(flightMetrics.averageSpeedBlocksPerSecond())
                + " rotation=(" + decimal(projectile.getXRot()) + "," + decimal(projectile.getYRot()) + ")"
                + " targetEntity=" + target
                + " targetPos=" + formatVec(projectile.getTargetPos())
                + " lastGuidancePos=" + formatVec(projectile.getLastGuidancePos())
                + " phase=" + phase
                + " source=" + source
                + " stage=" + stage
                + " radarOn=" + radarOn
                + " radarCatch=" + radarCatch
                + " radarLostTicks=" + projectile.getAutonomousSeekerLostTargetTick()
                + " motorBurning=" + motorBurning
                + " secondPulseStart=" + projectile.getSecondPulseStartTick()
                + " superTickMicros=" + micros(superTickNanos)
                + " rvpTickMicros=" + micros(rvpTickNanos)
                + " totalTickMicros=" + micros(totalTickNanos)
                + " changes=" + changes;
        // 写入本 tick 的完整状态，再把当前状态设为下一 tick 的比较基线。
        record(trace, projectile, Event.TICK, details);
        trace.updateLastState(phase, source, stage, target, radarOn, radarCatch, motorBurning);
    }

    /**
     * 记录命中、引信、爆炸等离散事件；details 仅在监测启用时求值。
     *
     * @param projectile 事件所属弹体
     * @param event 事件类型
     * @param details 惰性构造的事件详情
     */
    public static void noteEvent(RVP_BaseBullet projectile, Event event, Supplier<String> details) {
        if (!shouldTrace(projectile)) {
            return;
        }
        String resolved;
        try {
            // details 可能访问复杂运行状态，异常时不能反向破坏弹体主逻辑。
            resolved = details == null ? "" : details.get();
        } catch (RuntimeException e) {
            resolved = "<detail_error:" + e.getClass().getSimpleName() + ">";
        }
        // 统一通过 record 生成公共前缀并写入内存历史和文件。
        record(trace(projectile), projectile, event, resolved == null ? "" : resolved);
    }

    /**
     * 记录经过 {@link Entity#remove(Entity.RemovalReason)} 的移除请求。
     * 区块停止追踪和真正离开世界由 Forge EntityLeaveLevelEvent 分开记录。
     *
     * @param projectile 被请求移除的弹体
     * @param reason 移除原因
     */
    public static void noteRemoved(RVP_BaseBullet projectile, Entity.RemovalReason reason) {
        if (!shouldTrace(projectile)) {
            return;
        }
        ProjectileTrace trace = trace(projectile);
        // 同一 trace 只接受第一次移除请求，防止 discard/kill 重复调用刷日志。
        if (!trace.markRemovalRequested(reason)) {
            return;
        }
        // REMOVED 只表示“请求”，真正终止必须等待携带 reason 的 LEFT_LEVEL。
        record(trace, projectile, Event.REMOVED,
                "reason=" + reason
                        + " aliveBeforeRemove=" + projectile.isAlive()
                        + " life=" + projectile.life
                        + " position=" + formatVec(projectile.position())
                        + " velocity=" + formatVec(projectile.getDeltaMovement()));
    }

    /**
     * 处理 Forge EntityLeaveLevelEvent，并按 RemovalReason 是否为空区分非终止停止追踪和真正终止。
     * 查询仅使用 hasChunkAt/isPositionEntityTicking，不会为了调试同步加载区块。
     *
     * @param serverLevel 事件所属服务端世界
     * @param projectile 离开追踪集合的 RVP 弹体
     */
    public static void noteLeftLevel(ServerLevel serverLevel, RVP_BaseBullet projectile) {
        if (!ENABLED.get()) {
            return;
        }
        // EntityLeaveLevelEvent 可能发生在未经过 noteInitialized 的监测中途，按需补建 trace。
        ProjectileTrace trace = trace(projectile);
        Entity.RemovalReason reason = projectile.getRemovalReason();
        BlockPos pos = projectile.blockPosition();
        // 这两个查询均不触发区块加载，只描述事件发生时的真实状态。
        boolean loaded = serverLevel.hasChunkAt(pos);
        boolean entityTicking = serverLevel.isPositionEntityTicking(pos);
        long now = serverLevel.getGameTime();

        if (reason == null) {
            // reason 为空只代表 onTrackingEnd；设置 stalled/trackingEnded，但不能结束 trace。
            TrackingEndSnapshot snapshot = trace.markTrackingEnded();
            if (snapshot == null) {
                return;
            }
            // 立即记录 TRACKING_END，无需等待 40 tick watchdog 才解释日志停止。
            recordTrace(trace, now, Event.TRACKING_END,
                    "reason=<null>"
                            + " removalRequested=" + snapshot.removalRequested()
                            + " stalled=true"
                            + " trackingEnded=true"
                            + " leftLevel=false"
                            + " position=" + formatVec(projectile.position())
                            + " chunk=" + formatChunk(new ChunkPos(pos))
                            + " loaded=" + loaded
                            + " entityTicking=" + entityTicking
                            + " lastTickGameTime=" + snapshot.lastTickGameTime()
                            + " elapsedSinceTick=" + elapsed(now, snapshot.lastTickGameTime()));
            trimRetainedTraces(now);
            return;
        }

        // 携带 RemovalReason 才是权威终止事件，设置 leftLevel=true。
        LeftLevelSnapshot snapshot = trace.markLeftLevel(reason);
        if (snapshot == null) {
            return;
        }
        // LEFT_LEVEL 保留 nullable 之外的实际 reason、区块状态和距最后 tick 时长。
        recordTrace(trace, now, Event.LEFT_LEVEL,
                "reason=" + formatRemovalReason(reason)
                        + " removalRequested=" + snapshot.removalRequested()
                        + " stalled=" + snapshot.stalled()
                        + " trackingEnded=" + snapshot.trackingEnded()
                        + " position=" + formatVec(projectile.position())
                        + " chunk=" + formatChunk(new ChunkPos(pos))
                        + " loaded=" + loaded
                        + " entityTicking=" + entityTicking
                        + " lastTickGameTime=" + snapshot.lastTickGameTime()
                        + " elapsedSinceTick=" + elapsed(now, snapshot.lastTickGameTime()));
        // trace 已终止，容量超限时现在可以优先淘汰最旧终止记录。
        trimRetainedTraces(now);
    }

    /**
     * 服务端 watchdog：周期检查活动 trace，发现长时间未 tick 时记录一次 TICK_STALLED。
     * 本方法不持有实体引用，也不会强制加载最后位置区块。
     *
     * @param server 当前 Minecraft 服务端
     */
    public static void watchdogTick(MinecraftServer server) {
        if (!ENABLED.get()) {
            return;
        }
        // 使用主世界 gameTime 作为跨维度统一扫描时钟。
        long now = server.overworld().getGameTime();
        synchronized (RVP_ProjectileLifecycleDebug.class) {
            // 限制为每 WATCHDOG_SCAN_INTERVAL_TICKS 扫描一次，避免逐 tick 遍历全部 trace。
            if (lastWatchdogScanGameTime != Long.MIN_VALUE
                    && now >= lastWatchdogScanGameTime
                    && now - lastWatchdogScanGameTime < WATCHDOG_SCAN_INTERVAL_TICKS) {
                return;
            }
            lastWatchdogScanGameTime = now;
        }

        // 使用 Map 快照遍历，避免扫描期间 trace 新增/淘汰影响迭代。
        for (ProjectileTrace trace : new ArrayList<>(TRACES.values())) {
            // markStalledIfDue 原子完成阈值判断和 ACTIVE -> STALLED 转换。
            StallSnapshot snapshot = trace.markStalledIfDue(now, STALL_THRESHOLD_TICKS);
            if (snapshot == null) {
                continue;
            }
            // getLevel 只取得已存在的维度实例，hasChunkAt 不会同步生成新区块。
            ServerLevel level = server.getLevel(snapshot.dimension());
            boolean loaded = level != null && level.hasChunkAt(snapshot.lastBlockPos());
            boolean entityTicking = level != null && level.isPositionEntityTicking(snapshot.lastBlockPos());
            // 状态只转换一次，因此 watchdog 不会每次扫描重复写同一条停滞日志。
            recordTrace(trace, now, Event.TICK_STALLED,
                    "lastTickGameTime=" + snapshot.lastTickGameTime()
                            + " elapsed=" + elapsed(now, snapshot.lastTickGameTime())
                            + " lastTick=" + snapshot.lastTickCount()
                            + " lastPosition=" + formatBlockPos(snapshot.lastBlockPos())
                            + " lastChunk=" + formatChunk(snapshot.lastChunkPos())
                            + " loaded=" + loaded
                            + " entityTicking=" + entityTicking
                            + " removalRequested=" + snapshot.removalRequested()
                            + " trackingEnded=" + snapshot.trackingEnded()
                            + " leftLevel=false");
        }
        // 扫描结束后治理终止或超期 stalled trace。
        trimRetainedTraces(now);
    }

    /**
     * 生成当前全部 trace 的文本快照；不修改日志文件。
     *
     * @param player 执行命令的玩家，可为空
     * @return 可写入日志的多行快照
     */
    public static String dumpSnapshot(@Nullable ServerPlayer player) {
        StringBuilder out = new StringBuilder();
        out.append("=== RVP Projectile Lifecycle Debug ===\n")
                .append("enabled=").append(ENABLED.get()).append('\n')
                .append("logPath=").append(LOG_PATH).append('\n');
        if (player != null) {
            out.append("player=").append(player.getName().getString()).append('\n')
                    .append("dimension=").append(player.level().dimension().location()).append('\n');
        }
        // 固定快照并按创建顺序输出，避免 ConcurrentHashMap 的随机遍历顺序干扰对比。
        List<ProjectileTrace> traces = new ArrayList<>(TRACES.values());
        traces.sort(Comparator.comparingLong(trace -> trace.createdOrder));
        out.append("traceCount=").append(traces.size()).append('\n');
        for (ProjectileTrace trace : traces) {
            synchronized (trace) {
                out.append("--- entity=").append(trace.entityId)
                        .append(" uuid=").append(trace.uuid)
                        .append(" weapon=").append(trace.weaponId)
                        .append(" kind=").append(trace.weaponKind)
                        .append(" class=").append(trace.entityClass)
                        .append(" stalled=").append(trace.stalled)
                        .append(" trackingEnded=").append(trace.trackingEnded)
                        .append(" removalRequested=").append(trace.removalRequested)
                        .append(" leftLevel=").append(trace.leftLevel)
                        .append(" removalReason=").append(formatRemovalReason(trace.lastRemovalReason))
                        .append(" ---\n");
                for (String line : trace.history) {
                    out.append(line).append('\n');
                }
            }
        }
        return localizeLogText(out.toString());
    }

    /**
     * 将 {@link #dumpSnapshot(ServerPlayer)} 结果追加到独立日志，并用 begin/end 标记边界。
     *
     * @param player 执行 dump 的玩家，可为空
     */
    public static void appendSnapshot(@Nullable ServerPlayer player) {
        // 每行单独走 appendFileLog，以保持与普通事件相同的墙钟时间前缀。
        appendFileLog("snapshot_begin");
        for (String line : dumpSnapshot(player).split("\\R")) {
            appendFileLog("snapshot " + line);
        }
        appendFileLog("snapshot_end");
    }

    /**
     * 将实体格式化为“注册名#运行时ID”，供各事件统一引用。
     *
     * @param entity 待格式化实体，可为空
     * @return 稳定可读的实体标签
     */
    public static String formatEntity(@Nullable Entity entity) {
        if (entity == null) {
            return "<null>";
        }
        ResourceLocation typeId = entity.getType().builtInRegistryHolder().key().location();
        return typeId + "#" + entity.getId();
    }

    /**
     * 将三维向量格式化为固定三位小数；null 保留为显式占位符。
     *
     * @param vec 待格式化向量
     * @return 日志向量文本
     */
    public static String formatVec(@Nullable Vec3 vec) {
        if (vec == null) {
            return "<null>";
        }
        return "(" + decimal(vec.x) + "," + decimal(vec.y) + "," + decimal(vec.z) + ")";
    }

    /**
     * 使用 ROOT locale 格式化有限浮点数，避免系统区域设置改变小数点符号。
     *
     * @param value 待格式化数值
     * @return 三位小数或 NaN/Infinity 原始文本
     */
    public static String decimal(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** 非有限的“无可用距离”使用 null 占位，其他数值沿用统一三位小数格式。 */
    private static String decimalOrNull(double value) {
        return Double.isFinite(value) ? decimal(value) : "<null>";
    }

    /**
     * 解析当前有效目标点并计算直线剩余距离：实体目标优先，其次固定目标点，最后使用制导记忆点。
     */
    private static double resolveRemainingDistance(RVP_BaseBullet projectile) {
        Vec3 destination = null;
        Entity target = projectile.getTargetEntity();
        if (target != null && target.isAlive()) {
            destination = target.position();
        } else if (projectile.getTargetPos() != null) {
            destination = projectile.getTargetPos();
        } else if (projectile.getLastGuidancePos() != null) {
            destination = projectile.getLastGuidancePos();
        }
        return destination == null ? Double.NaN : projectile.position().distanceTo(destination);
    }

    /** 将纳秒耗时转为保留三位小数的微秒，兼顾短 Tick 的可观察精度。 */
    private static String micros(long nanos) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0L, nanos) / 1_000.0D);
    }

    /**
     * 判断弹体是否应被服务端监测；客户端实例始终排除，防止同一生命周期重复记录。
     *
     * @param projectile 候选弹体
     * @return 监测器已启用且为有效服务端弹体时返回 true
     */
    private static boolean shouldTrace(@Nullable RVP_BaseBullet projectile) {
        return ENABLED.get() && projectile != null && !projectile.level().isClientSide();
    }

    /**
     * 按 UUID 获取或创建 trace，并在新增后执行容量治理。
     *
     * @param projectile 用于建立身份快照的弹体
     * @return 该 UUID 唯一对应的 trace
     */
    private static ProjectileTrace trace(RVP_BaseBullet projectile) {
        // computeIfAbsent 保证并发事件只创建一份同 UUID trace。
        ProjectileTrace resolved = TRACES.computeIfAbsent(
                projectile.getUUID(), ignored -> new ProjectileTrace(projectile));
        trimRetainedTraces(projectile.level().getGameTime());
        return resolved;
    }

    /**
     * 从仍可访问的弹体刷新身份/时空快照，再委托无实体日志入口写入事件。
     *
     * @param trace 目标 trace
     * @param projectile 实时弹体
     * @param event 事件类型
     * @param details 事件详情
     */
    private static void record(ProjectileTrace trace, RVP_BaseBullet projectile, Event event, String details) {
        // 离散事件也更新最后已知位置和 tick，保证 watchdog 使用最新快照。
        trace.updateIdentitySnapshot(projectile);
        recordTrace(trace, projectile.level().getGameTime(), event, details);
    }

    /**
     * 仅依赖不可变 trace 快照写日志，供实体已停止 tick/离开追踪后使用。
     *
     * @param trace 目标 trace
     * @param gameTime 事件对应的服务器游戏时间
     * @param event 事件类型
     * @param details 事件详情
     */
    private static void recordTrace(ProjectileTrace trace, long gameTime, Event event, String details) {
        // 在 trace 锁内复制公共头部，后续字符串拼接不长期占用 trace 锁。
        TraceHeader header = trace.header();
        String line = "gameTime=" + gameTime
                + " entityId=" + header.entityId()
                + " uuid=" + header.uuid()
                + " weapon=" + header.weaponId()
                + " kind=" + header.weaponKind()
                + " class=" + header.entityClass()
                + " tick=" + header.lastTickCount()
                + " event=" + event
                + (details == null || details.isBlank() ? "" : " " + details);
        // 同一格式同时进入有界内存历史和独立文件。
        trace.append(line);
        appendFileLog(line);
    }

    /**
     * 在超过容量时优先淘汰最旧终止 trace，其次淘汰长期 stalled trace；活动 trace 不静默删除。
     *
     * @param currentGameTime 当前服务器游戏时间
     */
    private static synchronized void trimRetainedTraces(long currentGameTime) {
        while (TRACES.size() > MAX_RETAINED_PROJECTILES) {
            // LEFT_LEVEL 是权威终止状态，优先释放最旧的已完成历史。
            ProjectileTrace oldestFinished = TRACES.values().stream()
                    .filter(ProjectileTrace::hasLeftLevel)
                    .min(Comparator.comparingLong(trace -> trace.createdOrder))
                    .orElse(null);
            if (oldestFinished != null) {
                TRACES.remove(oldestFinished.uuid, oldestFinished);
                continue;
            }
            // 无终止 trace 可删时，才允许淘汰长期无法恢复的 stalled 记录。
            ProjectileTrace oldestExpiredStall = TRACES.values().stream()
                    .filter(trace -> trace.isExpiredStall(currentGameTime, STALLED_TRACE_RETENTION_TICKS))
                    .min(Comparator.comparingLong(trace -> trace.createdOrder))
                    .orElse(null);
            if (oldestExpiredStall != null) {
                TRACES.remove(oldestExpiredStall.uuid, oldestExpiredStall);
                appendFileLog("monitor trace_evicted reason=stalled_retention uuid=" + oldestExpiredStall.uuid);
                continue;
            }
            if (!activeTraceLimitWarningLogged) {
                // 剩余均为活动 trace 时不删除，只告警一次交给调试者处理。
                activeTraceLimitWarningLogged = true;
                appendFileLog("monitor warning=active_trace_limit_exceeded count=" + TRACES.size());
            }
            return;
        }
        activeTraceLimitWarningLogged = false;
    }

    /**
     * 线程安全地向独立日志追加一行，并按需创建父目录和文件。
     *
     * @param message 不含墙钟前缀和换行符的日志正文
     */
    private static synchronized void appendFileLog(String message) {
        try {
            // 日志目录可能在首次启用监测时尚不存在。
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] "
                    + localizeLogText(message) + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (isChineseOutput()) {
                LOGGER.error("[RVP][弹体生命周期调试] 无法追加调试日志到 {}", LOG_PATH, e);
            } else {
                LOGGER.error("[RVP][ProjectileLifecycleDebug] Failed to append debug log to {}", LOG_PATH, e);
            }
        }
    }

    /**
     * 在最终输出边界翻译日志，内部历史始终保留英文规范字段，因而可随指令无损切换语言。
     */
    private static String localizeLogText(String canonicalText) {
        if (canonicalText == null || outputLanguage == OutputLanguage.EN_US) {
            return canonicalText;
        }
        String localized = canonicalText;
        for (Event event : Event.values()) {
            localized = localized.replace("event=" + event.name(), "event=" + chineseEventName(event));
        }
        for (String[] field : ZH_FIELD_NAMES) {
            localized = localized.replace(field[0] + "=", field[1] + "=");
        }
        return localized
                .replace("=== RVP Projectile Lifecycle Debug ===", "=== RVP 弹体生命周期调试 ===")
                .replace("=<null>", "=<无>")
                .replace("=<none>", "=<无>")
                .replace("=true", "=是")
                .replace("=false", "=否")
                .replace("变化=none", "变化=无")
                .replace("处理=discard", "处理=安全丢弃")
                .replace("处理=spawn_child", "处理=生成子弹体")
                .replace("原因=stalled_retention", "原因=停滞记录过期")
                .replace("=NOT_LOADED", "=未加载")
                .replace("=NOT_ENTITY_TICKING", "=未进入实体Tick")
                .replace("=NOT_REQUESTED", "=尚未申请")
                .replace("=PATH_TRUNCATED", "=路径已截断")
                .replace("=INVALID_PATH", "=路径无效")
                .replace("=DISCARDED", "=已丢弃")
                .replace("=KILLED", "=已击杀")
                .replace("=UNLOADED_TO_CHUNK", "=随区块卸载")
                .replace("=UNLOADED_WITH_PLAYER", "=随玩家卸载")
                .replace("snapshot_begin", "快照开始")
                .replace("snapshot_end", "快照结束")
                .replace("snapshot ", "快照 ")
                .replace("monitor ", "监测器 ")
                .replace("warning=active_trace_limit_exceeded", "警告=活动追踪数量超过上限")
                .replace("trace_evicted", "追踪已淘汰");
    }

    /** 将稳定的内部事件枚举转换为中文显示名。 */
    private static String chineseEventName(Event event) {
        return switch (event) {
            case INITIALIZED -> "初始化完成";
            case SPAWN_READY -> "生成就绪";
            case TICK_STALLED -> "Tick停滞";
            case TICK_RESUMED -> "Tick恢复";
            case TICK -> "逐Tick状态";
            case CONFIG_MISSING -> "配置缺失";
            case SHOOTER_INVALID -> "发射者无效";
            case WAITING_FOR_CHUNK -> "等待区块";
            case CHUNK_READY_RESUME -> "区块就绪恢复";
            case CHUNK_WAIT_TIMEOUT -> "区块等待超时";
            case SUBMUNITION_TRIGGER -> "子弹药触发";
            case BLOCK_HIT -> "命中方块";
            case WALL_PENETRATION -> "穿透墙体";
            case ENTITY_HIT -> "命中实体";
            case LIVING_PENETRATION -> "穿透生物";
            case BOUNCE -> "跳弹";
            case DIRECT_DAMAGE -> "直接伤害";
            case FUSE -> "引信";
            case AIRBURST_SUPPRESSED -> "空爆受抑制";
            case EXPLOSION -> "爆炸";
            case DISPENSER -> "撒布";
            case LIFE_END -> "寿命结束";
            case REMOVED -> "请求移除";
            case TRACKING_END -> "追踪结束";
            case LEFT_LEVEL -> "离开世界";
        };
    }

    /** 将可空资源 ID 转为日志文本。 */
    private static String safeId(@Nullable ResourceLocation id) {
        return id == null ? "<null>" : id.toString();
    }

    /** 将可空移除原因转为日志文本；null 是 TRACKING_END 的重要语义，不能省略。 */
    private static String formatRemovalReason(@Nullable Entity.RemovalReason reason) {
        return reason == null ? "<null>" : reason.name();
    }

    /** 将可空区块坐标格式化为“(x,z)”。 */
    private static String formatChunk(@Nullable ChunkPos pos) {
        return pos == null ? "<null>" : "(" + pos.x + "," + pos.z + ")";
    }

    /** 将可空方块坐标格式化为“(x,y,z)”。 */
    private static String formatBlockPos(@Nullable BlockPos pos) {
        return pos == null ? "<null>" : "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    /**
     * 计算非负游戏刻差；未知时间使用 -1，防止 Long.MIN_VALUE 参与减法溢出。
     */
    private static long elapsed(long now, long then) {
        return then == Long.MIN_VALUE ? -1L : Math.max(0L, now - then);
    }

    /** 单枚弹体的有界生命周期历史和 watchdog 状态快照。 */
    private static final class ProjectileTrace {
        /** 最近一次观察到的运行时实体 ID，仅用于日志展示。 */
        private int entityId;
        /** 生命周期唯一主键，不随服务器 entityId 复用而变化。 */
        private final UUID uuid;
        /** 初始化时的武器资源 ID 文本，仅作标签，不参与弹种分支。 */
        private final String weaponId;
        /** 初始化时的类型化武器种类。 */
        private final String weaponKind;
        /** 实际弹体 Java 类名。 */
        private final String entityClass;
        /** trace 创建顺序，用于稳定排序和容量淘汰。 */
        private final long createdOrder = System.nanoTime();
        /** 实际耗时的单调时钟起点；不受系统墙钟校时影响。 */
        private final long trackingStartedNanos = createdOrder;
        /** 上一次采样的精确位置，用于累计实际经过的折线路径。 */
        @Nullable
        private Vec3 lastExactPosition;
        /** 从 trace 建立起累计的实际飞行路径长度，单位为格。 */
        private double traveledDistance;
        /** 有界内存日志历史，dump 命令从此处读取。 */
        private final Deque<String> history = new ArrayDeque<>();
        /** 最近一次观察到的维度，用于 watchdog 查找 ServerLevel。 */
        private ResourceKey<Level> lastDimension;
        /** 最近一次实时事件或 tick 对应的服务器 gameTime。 */
        private long lastTickGameTime;
        /** 最近一次观察到的方块坐标。 */
        private BlockPos lastBlockPos;
        /** 最近一次观察到的区块坐标。 */
        private ChunkPos lastChunkPos;
        /** 最近一次观察到的实体 tickCount。 */
        private int lastTickCount;
        /** 是否已进入未收到 tick 的停滞状态。 */
        private boolean stalled;
        /** 是否已收到无 RemovalReason 的 onTrackingEnd；该状态不是终止。 */
        private boolean trackingEnded;
        /** 是否已经过 Entity.remove(...) 发起移除请求。 */
        private boolean removalRequested;
        /** 是否已收到携带 RemovalReason 的权威 LEFT_LEVEL 终止事件。 */
        private boolean leftLevel;
        /** 最近一次已知移除原因；TRACKING_END 时允许为空。 */
        @Nullable
        private Entity.RemovalReason lastRemovalReason;
        /** 上一次制导阶段，用于计算 changes。 */
        @Nullable
        private String lastPhase;
        /** 上一次制导源，用于计算 changes。 */
        @Nullable
        private String lastSource;
        /** 上一次制导 stage 名称，用于计算 changes。 */
        @Nullable
        private String lastStage;
        /** 上一次目标实体标签，用于计算 changes。 */
        @Nullable
        private String lastTarget;
        /** 上一次自主导引头开机状态。 */
        private boolean lastRadarOn;
        /** 上一次自主导引头捕获状态。 */
        private boolean lastRadarCatch;
        /** 上一次发动机燃烧状态。 */
        private boolean lastMotorBurning;
        /** 是否已经建立过 changes 比较基线。 */
        private boolean stateInitialized;

        /**
         * 从首次观察到的弹体建立不可变身份和初始时空快照。
         */
        private ProjectileTrace(RVP_BaseBullet projectile) {
            this.entityId = projectile.getId();
            this.uuid = projectile.getUUID();
            this.weaponId = safeId(projectile.getWeaponId());
            this.weaponKind = projectile.getWeaponKind().name();
            this.entityClass = projectile.getClass().getSimpleName();
            // 构造时立即初始化 watchdog 所需字段，避免生成后尚未首 tick 就无法判定状态。
            updateIdentitySnapshot(projectile);
        }

        /**
         * 从实时弹体原子刷新 watchdog 和日志头所需的最后快照。
         *
         * @param projectile 当前仍可访问的弹体
         */
        private synchronized void updateIdentitySnapshot(RVP_BaseBullet projectile) {
            this.entityId = projectile.getId();
            this.lastDimension = projectile.level().dimension();
            this.lastTickGameTime = projectile.level().getGameTime();
            Vec3 currentPosition = projectile.position();
            if (lastExactPosition != null && isFinite(lastExactPosition) && isFinite(currentPosition)) {
                traveledDistance += lastExactPosition.distanceTo(currentPosition);
            }
            lastExactPosition = currentPosition;
            this.lastBlockPos = projectile.blockPosition().immutable();
            this.lastChunkPos = new ChunkPos(lastBlockPos);
            this.lastTickCount = projectile.tickCount;
        }

        /**
         * 处理一次恢复后的正常 tick：先返回旧停滞信息，再清除 stalled/trackingEnded 并刷新快照。
         *
         * @param projectile 重新收到 tick 的弹体
         * @return 曾经停滞时返回恢复快照，否则返回 null
         */
        @Nullable
        private synchronized ResumeSnapshot updateTickSnapshot(RVP_BaseBullet projectile) {
            ResumeSnapshot resumed = stalled
                    ? new ResumeSnapshot(elapsed(projectile.level().getGameTime(), lastTickGameTime),
                            lastChunkPos, trackingEnded)
                    : null;
            // 收到真实 tick 即证明实体重新进入 ticking，两个非终止状态应同时清除。
            stalled = false;
            trackingEnded = false;
            updateIdentitySnapshot(projectile);
            return resumed;
        }

        /**
         * 返回当前飞行数据快照。实际时间从 trace 建立时开始，包含区块等待及服务器卡顿时间。
         */
        private synchronized FlightMetrics flightMetrics() {
            double elapsedSeconds = Math.max(0L, System.nanoTime() - trackingStartedNanos) / 1_000_000_000.0D;
            double averageSpeed = elapsedSeconds > 0.0D ? traveledDistance / elapsedSeconds : 0.0D;
            return new FlightMetrics(traveledDistance, elapsedSeconds, averageSpeed);
        }

        /** 坐标必须全部有限，异常实体位置不得污染后续累计距离。 */
        private static boolean isFinite(Vec3 position) {
            return Double.isFinite(position.x)
                    && Double.isFinite(position.y)
                    && Double.isFinite(position.z);
        }

        /**
         * 标记首次显式移除请求；后续重复请求不再记录。
         *
         * @param reason 请求中的移除原因
         * @return 本次是否完成了首次状态转换
         */
        private synchronized boolean markRemovalRequested(Entity.RemovalReason reason) {
            if (removalRequested) {
                return false;
            }
            removalRequested = true;
            lastRemovalReason = reason;
            return true;
        }

        /**
         * 标记无 RemovalReason 的停止追踪事件。该转换立即进入 stalled，但不结束 trace。
         *
         * @return 首次 TRACKING_END 的快照；重复事件或已终止时返回 null
         */
        @Nullable
        private synchronized TrackingEndSnapshot markTrackingEnded() {
            if (trackingEnded || leftLevel) {
                return null;
            }
            trackingEnded = true;
            stalled = true;
            return new TrackingEndSnapshot(removalRequested, lastTickGameTime);
        }

        /**
         * 标记携带 RemovalReason 的权威离开世界事件，并结束 trace。
         *
         * @param reason 非空移除原因
         * @return 首次终止事件快照；已经终止时返回 null
         */
        @Nullable
        private synchronized LeftLevelSnapshot markLeftLevel(Entity.RemovalReason reason) {
            if (leftLevel) {
                return null;
            }
            // 终止实体必然也已经结束追踪，因此两个状态均设为 true。
            leftLevel = true;
            trackingEnded = true;
            lastRemovalReason = reason;
            return new LeftLevelSnapshot(removalRequested, stalled, trackingEnded, lastTickGameTime);
        }

        /**
         * watchdog 原子检查停滞阈值并完成 ACTIVE -> STALLED 转换。
         *
         * @param now 当前统一服务器 gameTime
         * @param threshold 停滞阈值
         * @return 首次达到阈值时的只读快照，否则返回 null
         */
        @Nullable
        private synchronized StallSnapshot markStalledIfDue(long now, int threshold) {
            if (leftLevel || stalled || lastTickGameTime == Long.MIN_VALUE
                    || elapsed(now, lastTickGameTime) < threshold) {
                return null;
            }
            stalled = true;
            return new StallSnapshot(lastDimension, lastTickGameTime, lastBlockPos, lastChunkPos,
                    lastTickCount, removalRequested, trackingEnded);
        }

        /** @return trace 是否已经收到权威终止事件。 */
        private synchronized boolean hasLeftLevel() {
            return leftLevel;
        }

        /**
         * 判断 stalled trace 是否超过容量淘汰保留期。
         */
        private synchronized boolean isExpiredStall(long now, int retentionTicks) {
            return stalled && !leftLevel && elapsed(now, lastTickGameTime) >= retentionTicks;
        }

        /** @return 当前公共日志头的不可变副本。 */
        private synchronized TraceHeader header() {
            return new TraceHeader(entityId, uuid, weaponId, weaponKind, entityClass, lastTickCount);
        }

        /**
         * 向有界历史追加一行；达到上限时丢弃最旧行而不是扩大内存。
         */
        private synchronized void append(String line) {
            if (history.size() >= MAX_HISTORY_LINES_PER_PROJECTILE) {
                history.removeFirst();
            }
            history.addLast(line);
        }

        /**
         * 比较当前和上一次制导/发动机状态，生成紧凑 changes 文本。
         */
        private synchronized String describeChanges(String phase, String source, String stage, String target,
                                                     boolean radarOn, boolean radarCatch, boolean motorBurning) {
            if (!stateInitialized) {
                return "initial";
            }
            List<String> changes = new ArrayList<>();
            addChange(changes, "phase", lastPhase, phase);
            addChange(changes, "source", lastSource, source);
            addChange(changes, "stage", lastStage, stage);
            addChange(changes, "target", lastTarget, target);
            if (lastRadarOn != radarOn) {
                changes.add("radarOn:" + lastRadarOn + "->" + radarOn);
            }
            if (lastRadarCatch != radarCatch) {
                changes.add("radarCatch:" + lastRadarCatch + "->" + radarCatch);
            }
            if (lastMotorBurning != motorBurning) {
                changes.add("motorBurning:" + lastMotorBurning + "->" + motorBurning);
            }
            return changes.isEmpty() ? "none" : String.join(",", changes);
        }

        /** 将当前制导/发动机状态保存为下一 tick 的比较基线。 */
        private synchronized void updateLastState(String phase, String source, String stage, String target,
                                                  boolean radarOn, boolean radarCatch, boolean motorBurning) {
            lastPhase = phase;
            lastSource = source;
            lastStage = stage;
            lastTarget = target;
            lastRadarOn = radarOn;
            lastRadarCatch = radarCatch;
            lastMotorBurning = motorBurning;
            stateInitialized = true;
        }

        /** 字符串字段发生变化时向 changes 列表追加“名称:旧值->新值”。 */
        private static void addChange(List<String> changes, String name, @Nullable String before, String after) {
            if (before == null ? after != null : !before.equals(after)) {
                changes.add(name + ":" + before + "->" + after);
            }
        }
    }

    /** 无实体日志入口使用的公共头部不可变快照。 */
    private record TraceHeader(
            /* 最近实体 ID，仅用于展示。 */ int entityId,
            /* 生命周期 UUID。 */ UUID uuid,
            /* 武器资源 ID 标签。 */ String weaponId,
            /* 类型化武器种类。 */ String weaponKind,
            /* 弹体 Java 类名。 */ String entityClass,
            /* 最近实体 tickCount。 */ int lastTickCount) {}

    /** 每条 TICK 日志使用的实际飞行距离、单调时钟耗时和平均速度快照。 */
    private record FlightMetrics(
            /* trace 建立后累计经过的路径长度，单位为格。 */ double traveledDistance,
            /* trace 建立后真实经过的时间，单位为秒。 */ double actualElapsedSeconds,
            /* 路径长度除以真实耗时，单位为格/秒。 */ double averageSpeedBlocksPerSecond) {}

    /** stalled 恢复时保留的旧时长、旧区块和旧停止追踪状态。 */
    private record ResumeSnapshot(
            /* 停滞持续游戏刻。 */ long stalledFor,
            /* 停滞前最后区块。 */ ChunkPos lastChunk,
            /* 停滞是否由停止追踪触发。 */ boolean trackingEnded) {}

    /** 无 RemovalReason 的停止追踪事件快照。 */
    private record TrackingEndSnapshot(
            /* 之前是否收到显式移除请求。 */ boolean removalRequested,
            /* 最后一次实时 tick/event 时间。 */ long lastTickGameTime) {}

    /** 携带 RemovalReason 的权威终止事件快照。 */
    private record LeftLevelSnapshot(
            /* 之前是否收到显式移除请求。 */ boolean removalRequested,
            /* 终止前是否已停滞。 */ boolean stalled,
            /* 终止时是否已经结束追踪。 */ boolean trackingEnded,
            /* 最后一次实时 tick/event 时间。 */ long lastTickGameTime) {}

    /** watchdog 记录 TICK_STALLED 时使用的不可变最后状态。 */
    private record StallSnapshot(
            /* 最后所在维度。 */ ResourceKey<Level> dimension,
            /* 最后一次实时 tick/event 时间。 */ long lastTickGameTime,
            /* 最后方块坐标。 */ BlockPos lastBlockPos,
            /* 最后区块坐标。 */ ChunkPos lastChunkPos,
            /* 最后实体 tickCount。 */ int lastTickCount,
            /* 是否收到显式移除请求。 */ boolean removalRequested,
            /* 是否已经结束追踪。 */ boolean trackingEnded) {}
}
