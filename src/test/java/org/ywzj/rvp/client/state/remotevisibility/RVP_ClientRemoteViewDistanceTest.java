package org.ywzj.rvp.client.state.remotevisibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ClientRemoteViewDistanceTest {
    /** 同一目标在调小视距后交给远距绘制，调大后回到真实实体绘制。 */
    @Test
    void changingClientRangeMovesHandoverBothWays() {
        double distanceSquared = 200 * 200;
        // 调用渲染器共用的生产边界判断，按 16→8→16 区块模拟游戏内动态调整。
        assertFalse(RVP_ClientRemoteViewDistance.isBeyondClientRange(distanceSquared, 16));
        assertTrue(RVP_ClientRemoteViewDistance.isBeyondClientRange(distanceSquared, 8));
        assertFalse(RVP_ClientRemoteViewDistance.isBeyondClientRange(distanceSquared, 16));
    }

    /** 边界本身保留原生显示，边界外接管，且无效位置不触发隐藏。 */
    @Test
    void horizontalBoundaryAndInvalidPositionsAreHandled() {
        // 调用生产边界判断，覆盖等号、边界两侧及异常坐标。
        assertFalse(RVP_ClientRemoteViewDistance.isBeyondClientRange(127.99 * 127.99, 8));
        assertFalse(RVP_ClientRemoteViewDistance.isBeyondClientRange(128 * 128, 8));
        assertTrue(RVP_ClientRemoteViewDistance.isBeyondClientRange(128.01 * 128.01, 8));
        assertFalse(RVP_ClientRemoteViewDistance.isBeyondClientRange(Double.NaN, 8));
        assertFalse(RVP_ClientRemoteViewDistance.isBeyondClientRange(Double.POSITIVE_INFINITY, 8));
    }
}
