package org.ywzj.rvp.server.remotevisibility;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.config.RVP_CommonConfig.VisibilityMode;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.Candidate;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.NormalizationResult;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.VehicleCategory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RVP_RemoteVehicleVisibilityPolicyTest {
    @Test
    void defaultMatrixMatchesConfiguredAuthority() {
        Set<VehicleCategory> all = Set.of(
                VehicleCategory.HELICOPTER,
                VehicleCategory.AIRCRAFT,
                VehicleCategory.GROUND_VEHICLES);

        assertEquals(all, RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(VehicleCategory.HELICOPTER));
        assertEquals(all, RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(VehicleCategory.AIRCRAFT));
        assertEquals(Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT),
                RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(VehicleCategory.GROUND_VEHICLES));
        assertEquals(Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT),
                RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(VehicleCategory.WALKING_PLAYERS));
    }

    @Test
    void duplicateTokensAreDeduplicated() {
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(
                List.of("helicopter", "helicopter", "aircraft"));

        assertEquals(Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT), result.accepted());
        assertEquals(Set.of(), result.unknown());
    }

    @Test
    void unknownUppercaseAndWhitespaceTokensAreRejected() {
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(
                List.of("ground_vehicles", "HELICOPTER", " aircraft", "unknown"));

        assertEquals(Set.of(VehicleCategory.GROUND_VEHICLES), result.accepted());
        assertEquals(Set.of("HELICOPTER", " aircraft", "unknown"), result.unknown());
    }

    /** 步行玩家矩阵键可作为观察者，但不能混入远距载具目标类型。 */
    @Test
    void walkingPlayerTokenIsObserverOnly() {
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(
                List.of("walking_players", "helicopter", "aircraft"));

        assertEquals(Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT), result.accepted());
        assertEquals(Set.of("walking_players"), result.unknown());
    }

    @Test
    void emptyListIsLegalAndResultIsImmutable() {
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(List.of());

        assertEquals(Set.of(), result.accepted());
        assertEquals(Set.of(), result.unknown());
        assertThrows(UnsupportedOperationException.class,
                () -> result.accepted().add(VehicleCategory.HELICOPTER));
    }

    /** OFF 与必须乘载具的两种模式不会向无载具观察者授权。 */
    @Test
    void closedAndVehicleRequiredModesRejectWalkingObserver() {
        List<Candidate> candidates = List.of(candidate(1, VehicleCategory.AIRCRAFT, 600, true));

        assertTrue(select(VisibilityMode.OFF, null, -1, Set.of(), 4096, 32, candidates).isEmpty());
        assertTrue(select(VisibilityMode.VEHICLE_OCCUPANTS, null, -1, Set.of(), 4096, 32, candidates).isEmpty());
        assertTrue(select(VisibilityMode.RADAR_DETECTED, null, -1, Set.of(), 4096, 32, candidates).isEmpty());
        assertEquals(List.of(1), ids(select(
                VisibilityMode.ALL_PLAYERS, null, -1, Set.of(), 4096, 32, candidates)));
    }

    /** 原生接管、雷达直视和服务端最大距离均使用计划规定的开闭边界。 */
    @Test
    void distanceAndRadarBoundariesAreAppliedExactly() {
        List<Candidate> candidates = List.of(
                candidate(1, VehicleCategory.AIRCRAFT, 512, true),
                candidate(2, VehicleCategory.AIRCRAFT, 512.01, false),
                candidate(3, VehicleCategory.AIRCRAFT, 1024, false),
                candidate(4, VehicleCategory.AIRCRAFT, 1024.01, false),
                candidate(5, VehicleCategory.AIRCRAFT, 2048, true),
                candidate(6, VehicleCategory.AIRCRAFT, 4096, true),
                candidate(7, VehicleCategory.AIRCRAFT, 4096.01, true));

        List<Candidate> selected = select(
                VisibilityMode.RADAR_DETECTED,
                VehicleCategory.HELICOPTER,
                99,
                Set.of(VehicleCategory.AIRCRAFT),
                4096,
                32,
                candidates);

        assertEquals(List.of(2, 3, 5, 6), ids(selected));
    }

    /** 观察者类型白名单在数量排序前过滤，空白名单合法且不会被绕过。 */
    @Test
    void observerTypeMatrixFiltersBeforeBudget() {
        List<Candidate> candidates = List.of(
                candidate(1, VehicleCategory.GROUND_VEHICLES, 550, true),
                candidate(2, VehicleCategory.HELICOPTER, 600, true),
                candidate(3, VehicleCategory.AIRCRAFT, 650, true));

        assertEquals(List.of(2), ids(select(
                VisibilityMode.VEHICLE_OCCUPANTS,
                VehicleCategory.GROUND_VEHICLES,
                99,
                Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT),
                4096,
                1,
                candidates)));
        assertTrue(select(
                VisibilityMode.VEHICLE_OCCUPANTS,
                VehicleCategory.GROUND_VEHICLES,
                99,
                Set.of(),
                4096,
                32,
                candidates).isEmpty());
    }

    /** 步行玩家使用 walking_players 矩阵项时可看见默认的两类空中目标。 */
    @Test
    void walkingPlayerObserverUsesWalkingPlayerMatrix() {
        List<Candidate> candidates = List.of(
                candidate(1, VehicleCategory.AIRCRAFT, 600, false),
                candidate(2, VehicleCategory.HELICOPTER, 700, false),
                candidate(3, VehicleCategory.GROUND_VEHICLES, 800, false));

        assertEquals(List.of(1, 2), ids(select(
                VisibilityMode.VEHICLE_OCCUPANTS,
                VehicleCategory.WALKING_PLAYERS,
                -1,
                Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT),
                4096,
                32,
                candidates)));
    }

    /** 自车排除后按距离和实体 ID 稳定排序并截断。 */
    @Test
    void selfExclusionAndStableTruncationUseDistanceThenEntityId() {
        List<Candidate> selected = select(
                VisibilityMode.ALL_PLAYERS,
                null,
                10,
                Set.of(),
                4096,
                2,
                List.of(
                        candidate(10, VehicleCategory.AIRCRAFT, 550, true),
                        candidate(8, VehicleCategory.AIRCRAFT, 700, true),
                        candidate(6, VehicleCategory.AIRCRAFT, 700, true),
                        candidate(4, VehicleCategory.AIRCRAFT, 900, true)));

        assertEquals(List.of(6, 8), ids(selected));
    }

    /** 调用纯策略的测试适配器。 */
    private static List<Candidate> select(
            VisibilityMode mode,
            VehicleCategory observerCategory,
            int observerVehicleId,
            Set<VehicleCategory> allowed,
            double maxDistance,
            int maxTargets,
            List<Candidate> candidates) {
        return RVP_RemoteVehicleVisibilityPolicy.selectTargets(
                mode, observerCategory, observerVehicleId, allowed, maxDistance, maxTargets, candidates);
    }

    /** 构造指定实际水平距离的候选。 */
    private static Candidate candidate(
            int entityId,
            VehicleCategory category,
            double distance,
            boolean radarDetected) {
        return new Candidate(entityId, category, distance * distance, radarDetected);
    }

    /** 提取稳定排序后的实体 ID。 */
    private static List<Integer> ids(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::entityId).toList();
    }
}
