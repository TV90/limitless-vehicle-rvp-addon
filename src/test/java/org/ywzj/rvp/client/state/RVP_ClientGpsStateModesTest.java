package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** GPS 模式参数化：客户端状态机的 FIFO 上限、FAST/RADAR 清点与 fastSet 语义测试。 */
class RVP_ClientGpsStateModesTest {

    private static final ResourceLocation DIM =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @BeforeEach
    void reset() {
        RVP_ClientGPSState.replace(RVP_ClientGPSState.Mode.SINGLE, java.util.List.of(), 0);
    }

    private static Vec3 p(double v) {
        return new Vec3(v, 64, 0);
    }

    /** MULTI 滑动窗口：标第 9 点（上限 8）后新点成末位、2-8 依次前移、1 丢弃。 */
    @Test
    void multiAddEvictsOldestBeyondCap() {
        RVP_ClientGPSState.setMode(RVP_ClientGPSState.Mode.MULTI);
        for (int i = 1; i <= 8; i++) {
            RVP_ClientGPSState.addPoint(DIM, p(i), 8);
        }
        assertEquals(8, RVP_ClientGPSState.getPointCount());
        assertEquals(p(1), RVP_ClientGPSState.getPoints().get(0).pos());
        // 第 9 点：1 丢弃，2-8 前移为 1-7，9 成为末位（第 8 点）
        RVP_ClientGPSState.addPoint(DIM, p(9), 8);
        assertEquals(8, RVP_ClientGPSState.getPointCount());
        assertEquals(p(2), RVP_ClientGPSState.getPoints().get(0).pos());
        assertEquals(p(9), RVP_ClientGPSState.getPoints().get(7).pos());
        assertEquals(8, RVP_ClientGPSState.getPoints().size());
    }

    /** SINGLE 模式追加退化为覆盖单点（上限无关）。 */
    @Test
    void singleModeAddOverwrites() {
        RVP_ClientGPSState.addPoint(DIM, p(1), 8);
        RVP_ClientGPSState.addPoint(DIM, p(2), 8);
        assertEquals(1, RVP_ClientGPSState.getPointCount());
        assertEquals(p(2), RVP_ClientGPSState.getPoints().get(0).pos());
    }

    /** 进入 FAST/RADAR 清空全部 GPS 点；fastSet 写单点但保持 FAST 模式。 */
    @Test
    void fastAndRadarEntryClearPointsAndFastSetKeepsMode() {
        RVP_ClientGPSState.setMode(RVP_ClientGPSState.Mode.MULTI);
        RVP_ClientGPSState.addPoint(DIM, p(1), 8);
        RVP_ClientGPSState.addPoint(DIM, p(2), 8);
        RVP_ClientGPSState.setMode(RVP_ClientGPSState.Mode.FAST);
        assertEquals(0, RVP_ClientGPSState.getPointCount());
        assertEquals(RVP_ClientGPSState.Mode.FAST, RVP_ClientGPSState.getMode());
        // FAST 开火键写点：覆盖装订点且模式保持 FAST（不被 set 的强制 SINGLE 踢出）
        RVP_ClientGPSState.fastSet(DIM, p(9));
        assertEquals(1, RVP_ClientGPSState.getPointCount());
        assertEquals(RVP_ClientGPSState.Mode.FAST, RVP_ClientGPSState.getMode());
        assertEquals(p(9), RVP_ClientGPSState.getArmedPoint().pos());
        // 再进 RADAR 同样清点
        RVP_ClientGPSState.setMode(RVP_ClientGPSState.Mode.RADAR);
        assertEquals(0, RVP_ClientGPSState.getPointCount());
        assertEquals(RVP_ClientGPSState.Mode.RADAR, RVP_ClientGPSState.getMode());
        assertNull(RVP_ClientGPSState.getArmedPoint());
    }

    /** 切回 SINGLE 只保留最后一个点（既有语义保持）。 */
    @Test
    void backToSingleKeepsLastPoint() {
        RVP_ClientGPSState.setMode(RVP_ClientGPSState.Mode.MULTI);
        for (int i = 1; i <= 3; i++) {
            RVP_ClientGPSState.addPoint(DIM, p(i), 8);
        }
        RVP_ClientGPSState.setMode(RVP_ClientGPSState.Mode.SINGLE);
        assertEquals(1, RVP_ClientGPSState.getPointCount());
        assertEquals(p(3), RVP_ClientGPSState.getPoints().get(0).pos());
    }
}
