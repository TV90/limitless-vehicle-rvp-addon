package org.ywzj.rvp.entity.gunner.behavior.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 E 共享观察扫描合并与性能统计回归测试。 */
class RVP_GunnerObservationServiceTest {

    /** 内建行为源码。 */
    private static final Path BUILTIN_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/builtin/RVP_BuiltinGunnerBehaviors.java");
    /** 外置雷达源码。 */
    private static final Path EXTERNAL_RADAR_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/GunnerExternalRadarController.java");
    /** 制导维持源码。 */
    private static final Path GUIDANCE_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/GunnerGuidedWeaponController.java");
    /** Gunner 目标筛选源码。 */
    private static final Path TARGETING_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/GunnerTargeting.java");
    /** 共享观察服务源码。 */
    private static final Path OBSERVATION_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/runtime/RVP_GunnerObservationService.java");

    @Test
    void repeatedObservationQueriesLoadUnderlyingSnapshotOnlyOnce() {
        AtomicInteger loads = new AtomicInteger();
        RVP_GunnerObservationQueryCache<Integer> cache = new RVP_GunnerObservationQueryCache<>(() -> {
            loads.incrementAndGet();
            return List.of(1, 2, 3, 4);
        });

        assertEquals(List.of(1, 2, 3, 4), cache.snapshot());
        assertEquals(List.of(1, 2, 3, 4), cache.snapshot());
        assertEquals(1, loads.get());
        assertEquals(1, cache.loadCount());
    }

    @Test
    void behaviorRadarAndGuidanceUseOnlySharedWorldTraversal() throws IOException {
        String builtins = Files.readString(BUILTIN_SOURCE);
        String externalRadar = Files.readString(EXTERNAL_RADAR_SOURCE);
        String guidance = Files.readString(GUIDANCE_SOURCE);
        String observations = Files.readString(OBSERVATION_SOURCE);

        assertFalse(builtins.contains("getEntities().getAll()"));
        assertFalse(builtins.contains("level().getEntities("));
        assertFalse(externalRadar.contains("getEntities().getAll()"));
        assertFalse(guidance.contains("getEntities().getAll()"));
        assertTrue(observations.contains("serverLevel.getEntities().getAll()"));
        assertTrue(builtins.contains("context.observations()"));
        assertTrue(externalRadar.contains("observations.loadedEntities()"));
        assertTrue(guidance.contains("observations.ownedProjectiles"));
    }

    @Test
    void ciwsFilteringDoesNotMutateReadOnlyObservationCandidates() throws IOException {
        String targeting = Files.readString(TARGETING_SOURCE);

        assertFalse(targeting.contains("candidates.removeIf("));
        assertTrue(targeting.contains("return !RVP_GunnerEngagementNet.isHardLockedFor("));
    }

    @Test
    void scenarioAccumulatorKeepsMsptAndScanAveragesForFixedCounts() {
        assertEquals(List.of(1, 8, 16, 32),
                List.copyOf(RVP_GunnerPerformanceRecorder.snapshots().keySet()));
        RVP_GunnerPerformanceRecorder.ScenarioAccumulator accumulator =
                new RVP_GunnerPerformanceRecorder.ScenarioAccumulator();
        accumulator.add(40_000_000L, 4_000_000L, 8L, 800L, 1_600L);
        accumulator.add(60_000_000L, 6_000_000L, 8L, 1_200L, 2_400L);

        RVP_GunnerPerformanceRecorder.ScenarioSnapshot snapshot = accumulator.snapshot(8);
        assertEquals(8, snapshot.gunnerCount());
        assertEquals(2L, snapshot.samples());
        assertEquals(50.0D, snapshot.averageMspt(), 1.0E-9D);
        assertEquals(60.0D, snapshot.maxMspt(), 1.0E-9D);
        assertEquals(5.0D, snapshot.averageBehaviorMillis(), 1.0E-9D);
        assertEquals(8.0D, snapshot.averageWorldTraversals(), 1.0E-9D);
        assertEquals(1_000.0D, snapshot.averageSnapshotEntities(), 1.0E-9D);
        assertEquals(2_000.0D, snapshot.averageCandidateChecks(), 1.0E-9D);
    }
}
