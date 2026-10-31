import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 读取撞机现场的 JFR（Java 性能录制），按时间段汇总分配采样和垃圾回收停顿；不会触发 GC。 */
public final class InspectCrashRecording {
    /** 分配统计的时间窗，单位秒；权重为抽样估计值，不等于精确堆占用。 */
    private static final long WINDOW_SECONDS = 10;

    public static void main(String[] args) throws Exception {
        // 参数仅包含录制路径；不读取启动命令行，避免输出登录凭据。
        Map<String, Long> allocations = new HashMap<>();
        Map<String, Long> windows = new HashMap<>();
        Map<String, Long> execution = new HashMap<>();
        Instant since = args.length > 1 ? Instant.parse(args[1]) : Instant.MIN;
        Set<Long> sampledThreads = new HashSet<>();
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                // 首枚分配样本可能携带录制开始前的累计权重，剔除后再做时间窗比较。
                if (type.equals("jdk.ObjectAllocationSample") && event.getThread() != null
                        && sampledThreads.add(event.getThread().getJavaThreadId())) continue;
                if (event.getStartTime().isBefore(since)) continue;
                if (type.equals("jdk.GarbageCollection")) {
                    System.out.printf("GC %s id=%d collector=%s cause=%s pause=%.3fms longest=%.3fms%n",
                            event.getStartTime(), event.getInt("gcId"), event.getString("name"),
                            event.getString("cause"), event.getDuration("sumOfPauses").toNanos() / 1e6,
                            event.getDuration("longestPause").toNanos() / 1e6);
                } else if (type.equals("jdk.ObjectAllocationSample")) {
                    RecordedThread thread = event.getThread();
                    String name = thread == null ? "unknown" : thread.getJavaName();
                    String object = event.getClass("objectClass").getName();
                    // 调用本脚本堆栈摘要，保留业务入口以及主要分配调用链。
                    String key = name + " | " + object + " | " + stack(event);
                    long weight = event.getLong("weight");
                    allocations.merge(key, weight, Long::sum);
                    long window = event.getStartTime().getEpochSecond() / WINDOW_SECONDS * WINDOW_SECONDS;
                    windows.merge(Instant.ofEpochSecond(window) + " | " + name, weight, Long::sum);
                } else if (type.equals("jdk.ExecutionSample")) {
                    RecordedThread thread = event.getThread("sampledThread");
                    // 调用同一堆栈摘要，统计采样时各线程正在执行哪些方法。
                    execution.merge((thread == null ? "unknown" : thread.getJavaName())
                            + " | " + stack(event), 1L, Long::sum);
                }
            }
        }
        // 调用汇总输出，分配以 MiB 计，执行热点以样本数计。
        printTop("ALLOCATION MiB (sample estimate)", allocations, 40, 1048576.0);
        printTop("10 SECOND WINDOWS MiB (sample estimate)", windows, 40, 1048576.0);
        printTop("EXECUTION SAMPLES", execution, 30, 1.0);
    }

    /** 提取前 32 层栈；不输出录制中可能存在的环境变量和启动参数。 */
    private static String stack(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        if (trace == null) return "no stack";
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (RecordedFrame frame : trace.getFrames()) {
            if (count++ == 32) break;
            if (!result.isEmpty()) result.append(" <- ");
            result.append(frame.getMethod().getType().getName()).append('.')
                    .append(frame.getMethod().getName()).append(':').append(frame.getLineNumber());
        }
        return result.toString();
    }

    /** 按数值降序输出有限条目，避免大型录制淹没终端。 */
    private static void printTop(String title, Map<String, Long> values, int limit, double divisor) {
        System.out.println(title);
        values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit).forEach(entry -> System.out.printf("%.2f %s%n", entry.getValue() / divisor, entry.getKey()));
    }
}
