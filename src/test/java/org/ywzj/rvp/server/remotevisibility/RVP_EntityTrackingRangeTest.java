package org.ywzj.rvp.server.remotevisibility;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RVP_EntityTrackingRangeTest {
    /** 同一种导弹随服务器视距变化，从 32 格到 512 格均需使用当时的实际上限。 */
    @Test
    void serverViewDistanceCapsLongRangeEntities() {
        // 调用生产范围计算，模拟实际注册半径为 4096 格的 RVP 弹体。
        assertEquals(32, RVP_EntityTrackingRange.limitToViewDistance(4096, 2));
        assertEquals(128, RVP_EntityTrackingRange.limitToViewDistance(4096, 8));
        assertEquals(512, RVP_EntityTrackingRange.limitToViewDistance(4096, 32));
    }

    /** 服务端实体广播倍率缩短后的实体半径可以比地形视距更小。 */
    @Test
    void scaledEntityRangeCanBeShorterThanServerViewDistance() {
        // 调用生产范围计算，确保低广播倍率下不会错误使用视距或原来的 512 格。
        assertEquals(64, RVP_EntityTrackingRange.limitToViewDistance(64, 32));
        assertEquals(256, RVP_EntityTrackingRange.limitToViewDistance(256, 32));
        assertEquals(0, RVP_EntityTrackingRange.limitToViewDistance(0, 32));
    }

    /** 与原版区块管理器保持相同的 2..32 区块限幅，避免使用未经处理的配置值。 */
    @Test
    void viewDistanceUsesVanillaChunkMapLimits() {
        // 调用生产范围计算，覆盖原版视距两端的限幅。
        assertEquals(32, RVP_EntityTrackingRange.limitToViewDistance(4096, 1));
        assertEquals(512, RVP_EntityTrackingRange.limitToViewDistance(4096, 64));
    }

    /** 低视距时仍提前准备快照，不得把负的预热距离平方后变成新的隐藏区。 */
    @Test
    void preparationOverlapStaysValidForSmallTrackingRanges() {
        // 调用生产交接范围计算，覆盖低视距、零半径及原有 512 格示例。
        assertEquals(0, RVP_EntityTrackingRange.preparationDistanceSquared(0));
        assertEquals(0, RVP_EntityTrackingRange.preparationDistanceSquared(32));
        assertEquals(0, RVP_EntityTrackingRange.preparationDistanceSquared(128));
        assertEquals(128 * 128, RVP_EntityTrackingRange.preparationDistanceSquared(256));
        assertEquals(384 * 384, RVP_EntityTrackingRange.preparationDistanceSquared(512));
    }

    /** 同服两名玩家的视距互不覆盖，任一玩家调大或调小只改变自己的交接范围。 */
    @Test
    void clientRangesAreIndependentAndUpdateBothWays() {
        var distances = new RVP_EntityTrackingRange.ClientViewDistances();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // 调用服务端正式使用的表，首次上报前沿用服务器值。
        assertEquals(32, distances.get(first, 32));
        distances.update(first, 8);
        distances.update(second, 16);
        assertEquals(128, RVP_EntityTrackingRange.limitToViewDistance(4096, 32, distances.get(first, 32)));
        assertEquals(256, RVP_EntityTrackingRange.limitToViewDistance(4096, 32, distances.get(second, 32)));
        distances.update(first, 2);
        assertEquals(32, RVP_EntityTrackingRange.limitToViewDistance(4096, 32, distances.get(first, 32)));
        assertEquals(16, distances.get(second, 32));
        distances.update(first, 32);
        assertEquals(512, RVP_EntityTrackingRange.limitToViewDistance(4096, 32, distances.get(first, 32)));
    }

    /** 客户端较大视距不能突破服务端视距或实体广播倍率缩短后的半径。 */
    @Test
    void clientRangeCannotExpandServerTracking() {
        // 调用最终交接半径计算，覆盖服务器视距更小、实体半径更小两种上限。
        assertEquals(128, RVP_EntityTrackingRange.limitToViewDistance(4096, 8, 32));
        assertEquals(64, RVP_EntityTrackingRange.limitToViewDistance(64, 32, 16));
        assertEquals(0, RVP_EntityTrackingRange.limitToViewDistance(0, 32, 16));
    }

    /** 断开连接删除上报，非法值也不能覆盖有效视距；不同服务器实例各持独立表。 */
    @Test
    void logoutAndSeparateServersDoNotReuseOldRange() {
        var firstServer = new RVP_EntityTrackingRange.ClientViewDistances();
        var secondServer = new RVP_EntityTrackingRange.ClientViewDistances();
        UUID player = UUID.randomUUID();
        // 调用正式范围表，模拟两台服务器中相同玩家身份与离服清理。
        firstServer.update(player, 8);
        secondServer.update(player, 16);
        assertThrows(IllegalArgumentException.class, () -> firstServer.update(player, 0));
        assertEquals(8, firstServer.get(player, 32));
        firstServer.remove(player);
        assertEquals(32, firstServer.get(player, 32));
        assertEquals(16, secondServer.get(player, 32));
    }
}
