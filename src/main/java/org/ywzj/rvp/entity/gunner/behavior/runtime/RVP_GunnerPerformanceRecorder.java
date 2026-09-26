package org.ywzj.rvp.entity.gunner.behavior.runtime;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.debug.RVP_DebugFlags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段 E 服务端性能记录器。
 *
 * <p>记录完整服务端 MSPT，并把同一 tick 内所有 Gunner 的行为耗时、扫描次数与候选量聚合。
 * 正常运行不输出日志；开启 {@code /rvpdebug flags gunner on} 后每 200 tick 输出一次限频统计。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_GunnerPerformanceRecorder {

    /** 日志入口。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 场景统计的固定 Gunner 数量。 */
    private static final int[] SCENARIO_COUNTS = {1, 8, 16, 32};
    /** 各固定场景的累计窗口。 */
    private static final Map<Integer, ScenarioAccumulator> SCENARIOS = createScenarios();
    /** 当前服务端 tick 的开始纳秒时间。 */
    private static long tickStartNanos;
    /** 当前服务端 tick 已执行行为的 Gunner 数。 */
    private static int tickGunnerCount;
    /** 当前服务端 tick 的 Gunner 行为总耗时。 */
    private static long tickBehaviorNanos;
    /** 当前服务端 tick 的世界实体全量遍历次数。 */
    private static long tickWorldTraversals;
    /** 当前服务端 tick 的快照实体总数。 */
    private static long tickSnapshotEntities;
    /** 当前服务端 tick 的派生候选检查次数。 */
    private static long tickCandidateChecks;
    /** 用于调试日志限频的服务端 tick 计数。 */
    private static long debugTickCounter;

    private RVP_GunnerPerformanceRecorder() {
    }

    /** ServerTick START 最高优先级：先于其他 Tick 处理器开始测量完整 MSPT。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        tickStartNanos = System.nanoTime();
        tickGunnerCount = 0;
        tickBehaviorNanos = 0L;
        tickWorldTraversals = 0L;
        tickSnapshotEntities = 0L;
        tickCandidateChecks = 0L;
    }

    /** ServerTick END 最低优先级：在其他 Tick 处理器之后结束完整 MSPT 测量。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (tickStartNanos == 0L) {
            return;
        }
        long msptNanos = System.nanoTime() - tickStartNanos;
        ScenarioAccumulator scenario = SCENARIOS.get(tickGunnerCount);
        if (scenario != null) {
            scenario.add(msptNanos, tickBehaviorNanos, tickWorldTraversals,
                    tickSnapshotEntities, tickCandidateChecks);
        }
        debugTickCounter++;
        if (RVP_DebugFlags.GUNNER.isEnabled() && tickGunnerCount > 0 && debugTickCounter % 200L == 0L) {
            LOGGER.info("[RVP-Gunner-Perf] gunner={} mspt={} behavior_ms={} scans={} snapshot_entities={} candidate_checks={}",
                    tickGunnerCount, millis(msptNanos), millis(tickBehaviorNanos), tickWorldTraversals,
                    tickSnapshotEntities, tickCandidateChecks);
        }
        tickStartNanos = 0L;
    }

    /** 由行为管理器在单个 Gunner tick 结束时提交观察与行为耗时。 */
    public static void recordGunnerTick(RVP_GunnerObservationService.Statistics statistics,
                                        long behaviorNanos) {
        tickGunnerCount++;
        tickBehaviorNanos += Math.max(0L, behaviorNanos);
        tickWorldTraversals += statistics.worldTraversals();
        tickSnapshotEntities += statistics.snapshotEntities();
        tickCandidateChecks += statistics.candidateChecks();
    }

    /** 返回 1/8/16/32 场景的累计快照，供诊断命令或测试读取。 */
    public static Map<Integer, ScenarioSnapshot> snapshots() {
        Map<Integer, ScenarioSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, ScenarioAccumulator> entry : SCENARIOS.entrySet()) {
            result.put(entry.getKey(), entry.getValue().snapshot(entry.getKey()));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 创建固定顺序的场景窗口。 */
    private static Map<Integer, ScenarioAccumulator> createScenarios() {
        Map<Integer, ScenarioAccumulator> result = new LinkedHashMap<>();
        for (int count : SCENARIO_COUNTS) {
            result.put(count, new ScenarioAccumulator());
        }
        return result;
    }

    /** 将纳秒转成保留三位小数的毫秒文本。 */
    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    /** 单个 Gunner 数量场景的累计器。 */
    static final class ScenarioAccumulator {
        /** 已记录服务端 tick 数。 */
        private long samples;
        /** 完整服务端 tick 总耗时。 */
        private long totalMsptNanos;
        /** Gunner 行为总耗时。 */
        private long totalBehaviorNanos;
        /** 世界实体全量遍历总次数。 */
        private long totalWorldTraversals;
        /** 快照实体总数。 */
        private long totalSnapshotEntities;
        /** 派生候选检查总次数。 */
        private long totalCandidateChecks;
        /** 观察到的最高服务端 tick 耗时。 */
        private long maxMsptNanos;

        /** 加入一个服务端 tick 样本。 */
        void add(long msptNanos, long behaviorNanos, long traversals,
                 long snapshotEntities, long candidateChecks) {
            samples++;
            totalMsptNanos += msptNanos;
            totalBehaviorNanos += behaviorNanos;
            totalWorldTraversals += traversals;
            totalSnapshotEntities += snapshotEntities;
            totalCandidateChecks += candidateChecks;
            maxMsptNanos = Math.max(maxMsptNanos, msptNanos);
        }

        /** 创建不可变平均值快照。 */
        ScenarioSnapshot snapshot(int gunnerCount) {
            if (samples == 0L) {
                return new ScenarioSnapshot(gunnerCount, 0L, 0.0D, 0.0D, 0.0D,
                        0.0D, 0.0D, 0.0D);
            }
            return new ScenarioSnapshot(gunnerCount, samples,
                    totalMsptNanos / 1_000_000.0D / samples,
                    maxMsptNanos / 1_000_000.0D,
                    totalBehaviorNanos / 1_000_000.0D / samples,
                    (double) totalWorldTraversals / samples,
                    (double) totalSnapshotEntities / samples,
                    (double) totalCandidateChecks / samples);
        }
    }

    /** 固定 Gunner 数量场景的累计性能快照。 */
    public record ScenarioSnapshot(
            /** 场景 Gunner 数量。 */ int gunnerCount,
            /** 已记录 tick 数。 */ long samples,
            /** 平均完整服务端 MSPT。 */ double averageMspt,
            /** 最大完整服务端 MSPT。 */ double maxMspt,
            /** 平均 Gunner 行为耗时，单位毫秒。 */ double averageBehaviorMillis,
            /** 平均世界实体全量遍历次数。 */ double averageWorldTraversals,
            /** 平均快照实体数。 */ double averageSnapshotEntities,
            /** 平均派生候选检查次数。 */ double averageCandidateChecks) {
    }
}
