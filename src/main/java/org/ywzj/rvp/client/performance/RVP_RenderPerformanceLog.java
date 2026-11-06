package org.ywzj.rvp.client.performance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 独立UTF-8逐行JSON日志；渲染线程只入有界队列，绝不转发到latest.log或控制台。 */
public final class RVP_RenderPerformanceLog implements RVP_RenderPerformanceRecorder.Sink {
    /** 最多缓存64个统计窗口/状态记录，磁盘异常也不无限增长内存。 */
    private static final int QUEUE_LIMIT = 64;
    /** 正式文件达到32MiB时只保留一个自有备份。 */
    private static final long DEFAULT_LIMIT = 32L * 1024 * 1024;
    /** 只在后台线程序列化，保留null表达未测得的GPU时间。 */
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    /** 快速启停时新旧会话可能短暂并存；文件轮转与追加只在后台串行执行。 */
    private static final Object FILE_LOCK = new Object();
    /** 当前会话的独立文件路径。 */
    private final Path path;
    /** 轮转阈值；测试可以使用小阈值，不碰用户日志。 */
    private final long limit;
    /** 由渲染线程提交、后台线程消费的不可变统计快照。 */
    private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
    /** 队列溢出丢弃次数；记录在下一条成功写入的数据上。 */
    private final AtomicLong dropped = new AtomicLong();
    /** 写盘异常只经状态界面反馈，不回退到主日志。 */
    private volatile String failure = "";
    /** 请求停止后排空已有队列，不阻塞调用方。 */
    private volatile boolean closing;
    /** 后台线程已正常退出，供退出钩子和测试读取。 */
    private volatile boolean finished;
    /** 唯一文件线程，仅在明确开启记录后创建。 */
    private final Thread worker;

    public RVP_RenderPerformanceLog(Path path) { this(path, DEFAULT_LIMIT); }

    public RVP_RenderPerformanceLog(Path path, long limit) {
        this.path = path.toAbsolutePath(); this.limit = Math.max(1024, limit);
        worker = new Thread(this::run, "RVP-screen-performance-log");
        worker.setDaemon(true); worker.start();
    }

    public Path path() { return path; }
    @Override public boolean offer(Map<String, Object> record) {
        if (closing || !failure.isEmpty() || !queue.offer(new LinkedHashMap<>(record))) { dropped.incrementAndGet(); return false; }
        return true;
    }
    @Override public void close() { closing = true; }
    @Override public String status() {
        return "日志队列=" + queue.size() + "，丢弃=" + dropped.get() + (failure.isEmpty() ? "" : "，文件错误=" + failure);
    }

    /** 仅测试和JVM退出钩子使用有限等待，正常渲染路径不得调用。 */
    public boolean awaitClosed(long millis) throws InterruptedException { worker.join(millis); return finished; }

    /** 后台顺序写入并按窗口刷新，错误时关闭本文件，保留可查询的原因。 */
    private void run() {
        try {
            while (!closing || !queue.isEmpty()) {
                Map<String, Object> entry = queue.poll(100, TimeUnit.MILLISECONDS);
                if (entry == null) { continue; }
                Map<String, Object> line = new LinkedHashMap<>(entry);
                line.put("written_at_utc", Instant.now().toString()); line.put("writer_dropped_records", dropped.get());
                String serialized = JSON.toJson(line) + '\n';
                long size = serialized.getBytes(StandardCharsets.UTF_8).length;
                synchronized (FILE_LOCK) {
                    Files.createDirectories(path.getParent());
                    long bytes = Files.exists(path) ? Files.size(path) : 0;
                    if (bytes > 0 && bytes + size > limit) {
                        // 只轮转本记录器自己的明确文件，不删除目录或改动游戏主日志。
                        Files.move(path, path.resolveSibling(path.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    // 每份汇总是一个完整追加单元，旧会话不会持有轮转前的文件句柄。
                    try (BufferedWriter writer = open()) { writer.write(serialized); }
                }
            }
        } catch (Exception failureToWrite) {
            failure = failureToWrite.toString(); dropped.addAndGet(queue.size()); queue.clear();
        } finally {
            finished = true;
        }
    }

    private BufferedWriter open() throws java.io.IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
