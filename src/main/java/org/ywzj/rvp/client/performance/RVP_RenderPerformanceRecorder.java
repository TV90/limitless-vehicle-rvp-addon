package org.ywzj.rvp.client.performance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** 通用CPU/GPU范围记录器；无Minecraft依赖，可用假时钟和假显卡验证所有异步边界。 */
public final class RVP_RenderPerformanceRecorder {
    /** 未开启时共用的空范围，不计时、不申请查询、不分配逐次对象。 */
    public static final Scope NOOP = new Scope();
    /** 在途和空闲查询对总上限，显卡迟迟不返回时也不无限申请。 */
    private static final int MAX_QUERY_PAIRS = 256;
    /** 显卡查询最多等待两秒，逐帧轮询且绝不阻塞等待。 */
    private static final long QUERY_TIMEOUT = 2_000_000_000L;
    /** 每阶段每窗口保留的分位数样本上限，长时间录制不增长内存。 */
    private static final int MAX_PERCENTILE_SAMPLES = 2048;
    /** 纳秒时钟；生产使用System.nanoTime。 */
    private final LongSupplier clock;
    /** 只提供时间戳查询的显卡后端，不使用不可嵌套的TIME_ELAPSED。 */
    private final GpuClock gpu;
    /** 已完成可重用的查询对。 */
    private final ArrayDeque<Pair> free = new ArrayDeque<>();
    /** 结束时间戳已发出、结果仍可能未完成的范围。 */
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    /** 当前帧嵌套范围；业务计数向祖先传播，父级统计明确包含子级。 */
    private final ArrayDeque<Scope> stack = new ArrayDeque<>();
    /** 已封口但仍等待GPU结果的统计窗口。 */
    private final ArrayDeque<Window> sealed = new ArrayDeque<>();
    /** 异步日志接收器，仅录制时持有。 */
    private Sink sink;
    /** 当前统计窗口，GPU晚到的结果始终回到它原属的窗口。 */
    private Window current;
    /** 已申请查询对数，包含空闲、打开和在途。 */
    private int allocated;
    /** 逻辑录制状态；关闭后只允许轮询已提交结果。 */
    private boolean enabled;
    /** 当前是否处于一个被记录的渲染帧。 */
    private boolean frameOpen;
    /** 本次会话是否确实支持GPU时间戳。 */
    private boolean gpuSupported;
    /** 时间戳有效位数，用于处理有限位宽的回绕。 */
    private int timestampBits;
    /** 当前会话累计渲染帧号。 */
    private long frame;
    /** 上一帧开始的CPU时钟，用于记录实际帧间隔。 */
    private long lastFrameStart;
    /** 报告窗口时长，默认两秒，可在F8或指令中调整。 */
    private long intervalNanos = 2_000_000_000L;
    /** 本次会话的显卡能力或错误原因；不会写入主日志。 */
    private String gpuReason = "not_started";
    /** 当前日志状态，供指令和F8读取。 */
    private String lastStatus = "性能记录已关闭";
    /** 快速启停后的日志仍可按会话区分，不依赖后台线程写入顺序。 */
    private String session = "";
    /** 会话的单调时钟起点，用于把窗口和手动标记放到同一时间轴。 */
    private long sessionStarted;

    /** 显卡时间戳最小接口；available为false时禁止调用result。 */
    public interface GpuClock {
        /** 返回有效位数；0表示不支持。 */ int timestampBits();
        /** 创建一个独立时间戳查询。 */ int create();
        /** 排队记录时间，不等待执行完成。 */ void stamp(int query);
        /** 非阻塞检查该结果是否可以读取。 */ boolean available(int query);
        /** 只读取已确认完成的纳秒时间戳。 */ long result(int query);
        /** 释放自有查询，不操作调用方已有查询。 */ void delete(int query);
    }

    /** 日志接口接收不可变快照；生产实现只排入有界队列，文件写入由后台线程完成。 */
    public interface Sink {
        /** 返回false表示队列满或写入已失败。 */ boolean offer(Map<String, Object> record);
        /** 请求排空后关闭，不等待磁盘。 */ void close();
        /** 返回队列丢弃数或文件故障信息。 */ String status();
    }

    public RVP_RenderPerformanceRecorder(LongSupplier clock, GpuClock gpu) { this.clock = clock; this.gpu = gpu; }

    /** 新会话；快速重新开启时显式标记旧会话未完成的查询，不假造GPU零耗时。 */
    public void start(Sink target, Map<String, Object> metadata) {
        if (sink != null) {
            // 调用安全封口，新旧会话不共享统计窗口或写入队列。
            stop(); finishPending(true);
        }
        sink = target; frame = 0; lastFrameStart = 0;
        session = java.util.UUID.randomUUID().toString();
        long now = clock.getAsLong();
        sessionStarted = now;
        current = new Window(now); enabled = true;
        try {
            timestampBits = gpu.timestampBits(); gpuSupported = timestampBits >= 30 && timestampBits <= 64;
            gpuReason = gpuSupported ? "available" : "timestamp_unsupported";
        } catch (RuntimeException | LinkageError failure) {
            gpuSupported = false; gpuReason = failure.toString();
        }
        Map<String, Object> header = new LinkedHashMap<>(metadata);
        header.put("type", "session_start"); header.put("schema_version", 1);
        header.put("started_at_utc", java.time.Instant.now().toString());
        header.put("gpu_timing", gpuReason); header.put("timestamp_bits", timestampBits);
        header.put("timing_semantics", "inclusive; parent and child rows must not be added together");
        header.put("pixel_semantics", "submitted upper bounds, not actual shaded samples or hardware bandwidth");
        header.put("draw_semantics", "instrumented screen-effect draws; internal model draws and GUI batching are not inferred");
        header.put("measurement_overhead", "timing includes instrumentation; first use may allocate timestamp queries");
        header.put("stages", Arrays.stream(RVP_RenderStage.values()).map(stage -> Map.of(
                "id", stage.id, "title", stage.title, "parent", stage.parent)).toList());
        offer(header); lastStatus = "性能记录已开启";
    }

    /** 只改变之后窗口的长度，不隐式开启录制。 */
    public void intervalSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 1 || seconds > 30) { throw new IllegalArgumentException("统计间隔允许1～30秒。"); }
        intervalNanos = (long) (seconds * 1_000_000_000L);
    }

    /** 每显示帧一次，菜单和游戏内均可回收上帧查询。 */
    public void beginFrame(Map<String, Object> context) {
        if (sink == null) { return; }
        // 调用非阻塞回收，关闭后也给最后几帧显卡结果正常落盘的机会。
        poll();
        if (!enabled) { finishPending(false); return; }
        if (frameOpen) { endFrame(); }
        long now = clock.getAsLong();
        if (current == null) { current = new Window(now); }
        frame++; frameOpen = true;
        if (current.frames++ == 0) { current.firstFrame = frame; }
        current.lastFrame = frame;
        if (frame > 1) { current.frameIntervals.add(now - lastFrameStart); }
        lastFrameStart = now;
        if (current.firstContext == null) { current.firstContext = Map.copyOf(context); }
        if (current.lastContext != null && !current.lastContext.equals(context)) { current.contextChanges++; }
        current.lastContext = Map.copyOf(context);
        // 调用同一范围接口记录整帧对照；它包含其它模组，日志不会称为RVP总耗时。
        begin(RVP_RenderStage.FRAME, true);
    }

    /** 每帧结束时统一封口；遗漏关闭的业务范围会被计数，而不会泄漏显卡查询。 */
    public void endFrame() {
        if (!frameOpen) { return; }
        while (!stack.isEmpty()) {
            if (stack.peek().stage != RVP_RenderStage.FRAME) { current.unbalancedScopes++; }
            stack.peek().close();
        }
        frameOpen = false;
        if (current != null && clock.getAsLong() - current.started >= intervalNanos) { seal(); }
        // 调用可用性检查，GPU未完成时只留在队列，不执行强制同步。
        poll();
        if (!enabled) { finishPending(false); }
    }

    /** 默认关闭的快速入口：只有录制帧内才分配范围和提交时间戳。 */
    public Scope begin(RVP_RenderStage stage, boolean timeGpu) {
        if (!enabled || !frameOpen || current == null) { return NOOP; }
        Scope scope = new Scope(this, stage, current, clock.getAsLong());
        Stats stats = current.stats.computeIfAbsent(stage, ignored -> new Stats());
        stats.calls++; stats.gpuRequested |= timeGpu;
        if (timeGpu && gpuSupported) {
            try {
                scope.pair = acquire();
                if (scope.pair == null) { stats.gpuMissing++; current.queryBudgetDrops++; }
                else { gpu.stamp(scope.pair.start); current.outstanding++; }
            } catch (RuntimeException | LinkageError failure) {
                if (scope.pair != null) { destroy(scope.pair); scope.pair = null; }
                stats.gpuMissing++; gpuSupported = false; gpuReason = failure.toString();
            }
        } else if (timeGpu) { stats.gpuMissing++; }
        stack.push(scope);
        return scope;
    }

    /** 工作量向所有已打开的祖先传播；日志父子关系为包含关系，不可重复求和。 */
    public void add(RVP_RenderMetric metric, long value) {
        if (!enabled || !frameOpen || value < 0) { return; }
        for (Scope scope : stack) {
            Stats stats = scope.window.stats.get(scope.stage);
            stats.counters[metric.ordinal()] += value; stats.knownCounters |= 1L << metric.ordinal();
        }
    }

    /** 手动标记当前对照场景，只进独立日志。 */
    public void mark(String message) {
        if (enabled && sink != null) {
            offer(Map.of("type", "mark", "frame", frame, "ms_since_session", (clock.getAsLong() - sessionStarted) / 1_000_000.0,
                    "message", message));
        }
    }

    /** 停止新增采样，给已排队的GPU结果最多两秒异步收尾。 */
    public void stop() {
        if (!enabled) { return; }
        endFrame(); enabled = false;
        if (current != null) { seal(); }
        lastStatus = "已停止新增采样，正在收尾显卡结果";
        finishPending(false);
    }

    /** 登出、退出或测试结束时允许明确丢弃未完成结果，缺失会写进统计而不是记为0。 */
    public void finishPending(boolean force) {
        if (sink == null || enabled) { return; }
        poll();
        if (force) {
            while (!pending.isEmpty()) { lose(pending.removeFirst()); }
        }
        flushWindows();
        if (!pending.isEmpty() || !stack.isEmpty()) { return; }
        while (!free.isEmpty()) { destroy(free.removeFirst()); }
        offer(Map.of("type", "session_end", "frames", frame, "gpu_status", gpuReason,
                "writer_status", sink.status()));
        sink.close(); lastStatus = "性能记录已关闭；" + sink.status(); sink = null;
    }

    public boolean enabled() { return enabled; }
    public boolean draining() { return sink != null && !enabled; }
    public String status() { return lastStatus + "；GPU=" + gpuReason + (sink == null ? "" : "；" + sink.status()); }

    /** 可重用查询对；最大数量包括打开的范围，防止深嵌套绕过预算。 */
    private Pair acquire() {
        if (!free.isEmpty()) { return free.removeFirst(); }
        if (allocated >= MAX_QUERY_PAIRS) { return null; }
        int start = gpu.create();
        if (start <= 0) { throw new IllegalStateException("GPU未能创建时间戳查询。"); }
        try {
            int end = gpu.create();
            if (end <= 0) { throw new IllegalStateException("GPU未能创建结束时间戳查询。"); }
            allocated++; return new Pair(start, end);
        } catch (RuntimeException | LinkageError failure) { gpu.delete(start); throw failure; }
    }

    /** 所有退出路径幂等，异常的业务绘制也能正确提交结束时间戳并释放范围。 */
    private void close(Scope scope) {
        if (scope.closed) { return; }
        scope.closed = true;
        Stats stats = scope.window.stats.get(scope.stage);
        if (stack.peek() != scope) { scope.window.unbalancedScopes++; }
        stack.remove(scope);
        if (scope.pair != null) {
            try {
                gpu.stamp(scope.pair.end);
                pending.addLast(new Pending(scope.pair, scope.window, stats, clock.getAsLong(), frame));
            } catch (RuntimeException | LinkageError failure) {
                scope.window.outstanding--; stats.gpuMissing++; destroy(scope.pair); gpuReason = failure.toString();
            }
        }
        stats.cpu.add(Math.max(0, clock.getAsLong() - scope.started));
    }

    /** 只读已完成查询；每次最多检查有界池中的条目，不等待、不glFinish、不回读画面。 */
    private void poll() {
        long now = clock.getAsLong();
        int count = pending.size();
        for (int index = 0; index < count; index++) {
            Pending item = pending.removeFirst();
            // 正常录制至少隔两帧再看结果，减少刚提交就轮询带来的驱动工作；停止收尾则只查可用性。
            if (enabled && frame - item.frame < 2 && now - item.issued < QUERY_TIMEOUT) {
                pending.addLast(item); continue;
            }
            try {
                if (gpu.available(item.pair.end) && gpu.available(item.pair.start)) {
                    long elapsed = gpu.result(item.pair.end) - gpu.result(item.pair.start);
                    if (timestampBits < 64) { elapsed &= (1L << timestampBits) - 1; }
                    item.window.outstanding--;
                    if (elapsed < 0) { item.stats.gpuMissing++; }
                    else { item.stats.gpu.add(elapsed); }
                    free.addLast(item.pair);
                } else if (now - item.issued >= QUERY_TIMEOUT) { lose(item); }
                else { pending.addLast(item); }
            } catch (RuntimeException | LinkageError failure) {
                gpuReason = failure.toString(); gpuSupported = false; lose(item);
            }
        }
        flushWindows();
    }

    /** 未完成/失效结果明确标记缺失，禁止把CPU时间替代GPU时间。 */
    private void lose(Pending item) { item.window.outstanding--; item.stats.gpuMissing++; destroy(item.pair); }

    /** 查询只属于本记录器；释放失败只记录状态，不影响游戏渲染。 */
    private void destroy(Pair pair) {
        try { gpu.delete(pair.start); gpu.delete(pair.end); }
        catch (RuntimeException | LinkageError failure) { gpuReason = failure.toString(); }
        allocated--;
    }

    private void seal() {
        current.ended = clock.getAsLong();
        if (current.frames > 0) { sealed.addLast(current); }
        current = null;
        flushWindows();
    }

    /** GPU数据仍归属原窗口；不会在下一个窗口里错误形成耗时尖峰。 */
    private void flushWindows() {
        while (sink != null && !sealed.isEmpty() && sealed.peekFirst().outstanding == 0) {
            Window window = sealed.removeFirst();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "window"); record.put("frames", window.frames);
            record.put("first_frame", window.firstFrame); record.put("last_frame", window.lastFrame);
            record.put("start_ms_since_session", (window.started - sessionStarted) / 1_000_000.0);
            record.put("end_ms_since_session", (window.ended - sessionStarted) / 1_000_000.0);
            record.put("gpu_reason", gpuReason);
            record.put("duration_ms", (window.ended - window.started) / 1_000_000.0);
            record.put("frame_interval_ms", window.frameIntervals.describe());
            record.put("fps_estimate", window.frameIntervals.count == 0 || window.frameIntervals.total <= 0 ? null
                    : 1_000_000_000.0 * window.frameIntervals.count / window.frameIntervals.total);
            record.put("context_first", window.firstContext); record.put("context_last", window.lastContext);
            record.put("context_changes", window.contextChanges); record.put("query_budget_drops", window.queryBudgetDrops);
            record.put("unbalanced_scopes", window.unbalancedScopes);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RVP_RenderStage stage : RVP_RenderStage.values()) {
                Stats stats = window.stats.get(stage);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", stage.id); row.put("title", stage.title); row.put("inclusive", true);
                row.put("calls", stats == null ? 0 : stats.calls);
                row.put("gpu_status", stats == null ? "not_executed" : !stats.gpuRequested ? "cpu_only"
                        : stats.gpu.count == 0 ? "unavailable" : stats.gpuMissing > 0 ? "partial" : "available");
                row.put("cpu_submit_ms", stats == null ? null : stats.cpu.describe());
                row.put("gpu_ms", stats == null || stats.gpu.count == 0 ? null : stats.gpu.describe());
                row.put("cpu_ms_per_frame", stats == null ? null : stats.cpu.total / 1_000_000.0 / window.frames);
                row.put("gpu_ms_per_frame", stats == null || stats.gpu.count == 0 || stats.gpuMissing > 0 ? null
                        : stats.gpu.total / 1_000_000.0 / window.frames);
                row.put("gpu_missing_samples", stats == null ? 0 : stats.gpuMissing);
                Map<String, Long> metrics = new LinkedHashMap<>();
                if (stats != null) {
                    for (RVP_RenderMetric metric : RVP_RenderMetric.values()) {
                        if ((stats.knownCounters & 1L << metric.ordinal()) != 0) { metrics.put(metric.id, stats.counters[metric.ordinal()]); }
                    }
                }
                row.put("metrics", metrics); rows.add(row);
            }
            record.put("stages", rows); offer(record);
        }
    }

    /** 每行携带稳定会话号，后台写入和不同会话的收尾互不混淆。 */
    private void offer(Map<String, Object> record) {
        Map<String, Object> line = new LinkedHashMap<>(record); line.put("session", session); sink.offer(line);
    }

    /** 可选GUI收尾动作仅在显式录制时安装；保证延迟提交的顶点在结束时间戳之前绘制。 */
    public static final class Scope implements AutoCloseable {
        /** 所属记录器；空范围为null。 */ private final RVP_RenderPerformanceRecorder owner;
        /** 业务阶段；空范围为null。 */ private final RVP_RenderStage stage;
        /** 此范围归属的统计窗口。 */ private final Window window;
        /** CPU开始时间。 */ private final long started;
        /** 显卡查询对，CPU专用/不支持时为null。 */ private Pair pair;
        /** GUI显式flush等只在录制时执行的收尾动作。 */ private Runnable endAction;
        /** 幂等关闭标记。 */ private boolean closed;

        private Scope() { owner = null; stage = null; window = null; started = 0; }
        private Scope(RVP_RenderPerformanceRecorder owner, RVP_RenderStage stage, Window window, long started) {
            this.owner = owner; this.stage = stage; this.window = window; this.started = started;
        }
        /** 仅真实范围保存回调，空范围不创建绘制动作。 */
        public Scope endAction(Runnable action) { if (owner != null) { endAction = action; } return this; }
        @Override public void close() {
            if (owner == null || closed) { return; }
            try { if (endAction != null) { endAction.run(); } }
            finally { owner.close(this); }
        }
    }

    /** 一对互不影响其它模组计时范围的时间戳。 */
    private record Pair(
            /** 开始时间戳查询编号。 */ int start,
            /** 结束时间戳查询编号。 */ int end) { }
    /** GPU晚到结果携带原属窗口和阶段。 */
    private record Pending(
            /** 此范围独有的查询对。 */ Pair pair,
            /** 结果应回填的原始窗口。 */ Window window,
            /** 结果应回填的原始业务阶段。 */ Stats stats,
            /** 结束查询发出时的CPU时钟，用于超时。 */ long issued,
            /** 查询所属显示帧，正常录制至少隔两帧再轮询。 */ long frame) { }
    /** 固定时间窗口，未完成GPU结果只增加有界等待，不阻塞。 */
    private static final class Window {
        /** 窗口开始时钟。 */ final long started;
        /** 窗口结束时钟。 */ long ended;
        /** 记录的渲染帧数。 */ long frames;
        /** 本窗口第一帧会话编号，用于对齐手动标记与写盘丢弃。 */ long firstFrame;
        /** 本窗口最后一帧会话编号。 */ long lastFrame;
        /** 尚未完成的GPU范围数。 */ int outstanding;
        /** 查询池满而保留CPU但未测GPU的次数。 */ int queryBudgetDrops;
        /** 作用域未配对次数。 */ int unbalancedScopes;
        /** 分辨率/世界/光影状态变化次数。 */ int contextChanges;
        /** 第一个渲染帧的环境。 */ Map<String, Object> firstContext;
        /** 最后一个渲染帧的环境。 */ Map<String, Object> lastContext;
        /** 实际相邻帧开始间隔，包含CPU/GPU等待等整体因素。 */ final Samples frameIntervals = new Samples();
        /** 当前窗口的阶段统计。 */ final Map<RVP_RenderStage, Stats> stats = new EnumMap<>(RVP_RenderStage.class);
        Window(long started) { this.started = started; }
    }
    /** 每阶段的CPU/GPU统计和已知工作量。 */
    private static final class Stats {
        /** 真实进入该阶段的次数。 */ long calls;
        /** 是否请求显卡计时，区别于CPU专用准备阶段。 */ boolean gpuRequested;
        /** 未完成、不支持或超预算的GPU次数。 */ long gpuMissing;
        /** CPU提交时间。 */ final Samples cpu = new Samples();
        /** 有效GPU执行时间。 */ final Samples gpu = new Samples();
        /** 按指标枚举保存累计计数。 */ final long[] counters = new long[RVP_RenderMetric.values().length];
        /** 已接通的指标位图；未接通的指标留空，不假写0。 */ long knownCounters;
    }
    /** 有界分位数样本；平均和最大值始终覆盖全部数据，P95对超额数据使用最近2048个样本。 */
    private static final class Samples {
        /** 参与均值的完整样本数。 */ long count;
        /** 所有样本纳秒总和。 */ double total;
        /** 所有样本的最大纳秒值。 */ long max;
        /** P95使用的有界环形样本。 */ final long[] values = new long[MAX_PERCENTILE_SAMPLES];
        void add(long nanos) { values[(int) (count++ % values.length)] = nanos; total += nanos; max = Math.max(max, nanos); }
        Map<String, Object> describe() {
            if (count == 0) { return Map.of("samples", 0); }
            int size = (int) Math.min(count, values.length);
            long[] sorted = Arrays.copyOf(values, size); Arrays.sort(sorted);
            return Map.of("samples", count, "avg", total / count / 1_000_000.0,
                    "max", max / 1_000_000.0, "p95", sorted[(int) Math.ceil(size * 0.95) - 1] / 1_000_000.0,
                    "p95_samples", size);
        }
    }
}
