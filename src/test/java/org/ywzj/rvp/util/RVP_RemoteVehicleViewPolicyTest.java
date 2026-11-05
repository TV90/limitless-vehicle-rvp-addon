package org.ywzj.rvp.util;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_CommonConfig.VisibilityMode;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.Candidate;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.VehicleCategory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证多人平衡、单人豁免、视距动态收紧及授权入口的组合行为。 */
class RVP_RemoteVehicleViewPolicyTest {
    /** 专服与 LAN 均不能由客户端单方面请求放开。 */
    @Test
    void multiplayerRequiresBothSidesWhilePrivateSingleplayerIsExempt() {
        assertFalse(policy(true, false, false, true, 32, 8).allowBeyondViewDistance());
        assertFalse(policy(false, true, false, true, 32, 8).allowBeyondViewDistance());
        assertFalse(policy(true, false, true, false, 32, 8).allowBeyondViewDistance());
        assertTrue(policy(true, false, true, true, 32, 8).allowBeyondViewDistance());
        assertTrue(policy(false, false, false, true, 32, 8).allowBeyondViewDistance());
        assertFalse(policy(false, false, true, false, 32, 8).allowBeyondViewDistance());
    }

    /** 实际视距由较小的一侧决定，边界外仅低空目标受服务端默认限制。 */
    @Test
    void lowVehiclesUseEachConnectionsActualViewDistance() {
        var first = policy(true, false, false, true, 32, 8);
        var second = policy(true, false, false, true, 32, 16);
        assertTrue(first.allowsDistance(128 * 128, 32, true, () -> 0));
        assertFalse(first.allowsDistance(129 * 129, 32, true, () -> 25));
        assertTrue(second.allowsDistance(129 * 129, 32, true, () -> 25));
        assertTrue(first.allowsDistance(129 * 129, 32, true, () -> 25.01));
        var smallerServer = policy(true, false, false, true, 8, 32);
        assertFalse(smallerServer.allowsDistance(129 * 129, 32, true, () -> 24.99));
    }

    /** 玩家调低视距或关闭客户端偏好后，无需等待下一份快照即可收紧显示。 */
    @Test
    void clientCanTightenImmediatelyAndUnknownTerrainCannotGrantVisibility() {
        var limited = policy(true, false, false, true, 32, 8);
        assertFalse(limited.allowsDistance(100 * 100, 4, true, () -> 25));
        assertTrue(limited.allowsDistance(100 * 100, 4, true, () -> 30));
        assertFalse(limited.allowsDistance(600 * 600, 8, false, () -> 200));
        assertFalse(limited.allowsDistance(600 * 600, 8, true, () -> Double.NaN));
        assertFalse(limited.allowsDistance(600 * 600, 8, true, () -> Double.POSITIVE_INFINITY));
        var allowed = policy(true, false, true, true, 32, 8);
        assertTrue(allowed.allowsDistance(600 * 600, 8, true, () -> 0));
        assertFalse(allowed.allowsDistance(600 * 600, 8, false, () -> 200));
        assertTrue(allowed.allowsDistance(128 * 128, 8, false, () -> 0));
        assertFalse(allowed.allowsDistance(Double.NaN, 8, true, () -> 200));
        assertFalse(allowed.allowsDistance(Double.POSITIVE_INFINITY, 8, true, () -> 200));
        assertFalse(allowed.allowsDistance(-1, 8, true, () -> 200));
    }

    /** 同一世界开放 LAN 后，移除低空目标的授权并保留高空目标的数量预算。 */
    @Test
    void publishingSingleplayerRetractsOnlyLowFarTargetsBeforeLeaseCollection() {
        List<Candidate> targets = List.of(
                new Candidate(1, VehicleCategory.GROUND_VEHICLES, 128 * 128, true, 128),
                new Candidate(2, VehicleCategory.GROUND_VEHICLES, 200 * 200, true, 128),
                new Candidate(3, VehicleCategory.GROUND_VEHICLES, 1000 * 1000, true, 128));
        var privatePolicy = policy(false, false, false, true, 8, 8);
        var publishedPolicy = policy(false, true, false, true, 8, 8);
        var before = RVP_RemoteVehicleVisibilityPolicy.selectTargets(
                VisibilityMode.VEHICLE_OCCUPANTS, VehicleCategory.GROUND_VEHICLES, 99,
                Set.of(VehicleCategory.GROUND_VEHICLES), 4096, 1, targets.stream()
                        .filter(target -> privatePolicy.allowsDistance(target.horizontalDistanceSq(), 8,
                                true, () -> target.entityId() == 3 ? 26 : 25))
                        .toList());
        var after = RVP_RemoteVehicleVisibilityPolicy.selectTargets(
                VisibilityMode.VEHICLE_OCCUPANTS, VehicleCategory.GROUND_VEHICLES, 99,
                Set.of(VehicleCategory.GROUND_VEHICLES), 4096, 1, targets.stream()
                        .filter(target -> publishedPolicy.allowsDistance(target.horizontalDistanceSq(), 8,
                                true, () -> target.entityId() == 3 ? 26 : 25))
                        .toList());
        assertEquals(List.of(2), before.stream().map(Candidate::entityId).toList());
        assertEquals(List.of(3), after.stream().map(Candidate::entityId).toList());
    }

    /** 协议范围必须合法，高度未知时不授予超视距显示。 */
    @Test
    void defaultsAndInvalidWireRangesStayBounded() {
        assertFalse(RVP_RemoteVehicleViewPolicy.DEFAULT.allowBeyondViewDistance());
        assertFalse(RVP_RemoteVehicleViewPolicy.DEFAULT.allowsDistance(600 * 600, 32, true, () -> Double.NaN));
        for (int invalid : new int[]{-1, 0, 1, 33, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new RVP_RemoteVehicleViewPolicy(true, invalid));
        }
    }

    /** 调用生产连接策略，参数依次描述服务端类型、公开状态、两端许可和视距。 */
    private static RVP_RemoteVehicleViewPolicy policy(
            boolean dedicated, boolean published, boolean serverAllowed,
            boolean clientRequested, int serverChunks, int clientChunks) {
        return RVP_RemoteVehicleViewPolicy.forConnection(
                dedicated, published, serverAllowed, clientRequested, serverChunks, clientChunks);
    }
}
