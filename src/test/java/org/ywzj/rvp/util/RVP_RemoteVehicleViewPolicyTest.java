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

    /** 取两端实际视距较小值，短实体广播范围不改变玩家的地形视距。 */
    @Test
    void effectiveRangeUsesEachConnectionsActualViewDistance() {
        var first = policy(true, false, false, true, 32, 8);
        var second = policy(true, false, false, true, 32, 16);
        assertEquals(128.0, first.maximumDistance(4096));
        assertEquals(256.0, second.maximumDistance(4096));
        assertEquals(128.0, policy(true, false, false, true, 8, 32).maximumDistance(4096));
        assertEquals(64.0, first.maximumDistance(64));
        assertEquals(4096.0, policy(true, false, true, true, 32, 8).maximumDistance(4096));
    }

    /** 玩家调低视距或关闭客户端偏好后，不等下一份快照即可停画远处目标。 */
    @Test
    void clientCanTightenImmediatelyButCannotRelaxServerRestriction() {
        var denied = policy(true, false, false, true, 32, 8);
        assertTrue(denied.allowsDistance(128 * 128, 32, true));
        assertFalse(denied.allowsDistance(128.01 * 128.01, 32, true));
        assertFalse(denied.allowsDistance(100 * 100, 4, true));
        var allowed = policy(true, false, true, true, 32, 8);
        assertTrue(allowed.allowsDistance(600 * 600, 8, true));
        assertFalse(allowed.allowsDistance(600 * 600, 8, false));
        assertTrue(allowed.allowsDistance(128 * 128, 8, false));
        assertFalse(allowed.allowsDistance(Double.NaN, 8, true));
        assertFalse(allowed.allowsDistance(Double.POSITIVE_INFINITY, 8, true));
        assertFalse(allowed.allowsDistance(-1, 8, true));
    }

    /** 同一单人世界开放 LAN 后立即撤回超视距授权，原目标不会进入租约授权集合。 */
    @Test
    void publishingSingleplayerRetractsFarTargetsBeforeLeaseCollection() {
        List<Candidate> targets = List.of(
                new Candidate(1, VehicleCategory.GROUND_VEHICLES, 128 * 128, true, 128),
                new Candidate(2, VehicleCategory.GROUND_VEHICLES, 200 * 200, true, 128),
                new Candidate(3, VehicleCategory.GROUND_VEHICLES, 1000 * 1000, true, 128));
        var privatePolicy = policy(false, false, false, true, 8, 8);
        var publishedPolicy = policy(false, true, false, true, 8, 8);
        var before = RVP_RemoteVehicleVisibilityPolicy.selectTargets(
                VisibilityMode.VEHICLE_OCCUPANTS, VehicleCategory.GROUND_VEHICLES, 99,
                Set.of(VehicleCategory.GROUND_VEHICLES), privatePolicy.maximumDistance(4096), 32, targets);
        var after = RVP_RemoteVehicleVisibilityPolicy.selectTargets(
                VisibilityMode.VEHICLE_OCCUPANTS, VehicleCategory.GROUND_VEHICLES, 99,
                Set.of(VehicleCategory.GROUND_VEHICLES), publishedPolicy.maximumDistance(4096), 32, targets);
        assertEquals(List.of(2, 3, 1), before.stream().map(Candidate::entityId).toList());
        assertEquals(List.of(1), after.stream().map(Candidate::entityId).toList());
    }

    /** 协议范围必须合法，默认策略不会在首次同步前授予远距显示。 */
    @Test
    void defaultsAndInvalidWireRangesStayBounded() {
        assertFalse(RVP_RemoteVehicleViewPolicy.DEFAULT.allowBeyondViewDistance());
        assertFalse(RVP_RemoteVehicleViewPolicy.DEFAULT.allowsDistance(600 * 600, 32, true));
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
