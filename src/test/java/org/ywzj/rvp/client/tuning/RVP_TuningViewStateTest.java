package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.tuning.RVP_TuningViewState.Bounds;
import org.ywzj.rvp.client.tuning.RVP_TuningViewState.Snapshot;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证关闭/重新打开、分辨率变化和四边缩放，不启动Minecraft窗口。 */
class RVP_TuningViewStateTest {
    @Test
    void reopeningRestoresExactWindowAndCurrentConfigurationLocation() {
        RVP_TuningViewState state = new RVP_TuningViewState();
        Bounds movedAndResized = new Bounds(40, 24, 510, 270);
        Set<String> collapsed = new HashSet<>(Set.of("screen_space/global", "screen_space/engine"));
        // 调用与Screen离开时相同的快照入口，模拟先移动/缩放并查看较后参数，再按F8关闭。
        state.remember(new Snapshot(movedAndResized, 640, 338, "screen_space", "brightness", collapsed, 12, 421.5));
        collapsed.clear();
        Bounds defaults = new Bounds(231, 188, 320, 150);
        assertEquals(movedAndResized, state.windowFor(640, 338, defaults));
        Snapshot restored = state.snapshot().orElseThrow();
        assertEquals("screen_space", restored.selectedModule());
        assertEquals("brightness", restored.query());
        assertEquals(Set.of("screen_space/global", "screen_space/engine"), restored.collapsed());
        assertEquals(12, restored.navigationScroll());
        assertEquals(421.5, restored.parameterScroll());
        assertThrows(UnsupportedOperationException.class, () -> restored.collapsed().clear());
    }

    @Test
    void resizeCanExpandTopOfInitialBottomAlignedWindowWithoutMovingTheOppositeEdge() {
        Bounds initial = new Bounds(231, 188, 320, 150);
        Bounds taller = initial.resized(RVP_TuningViewState.TOP, 0, -100, 640, 338);
        assertEquals(new Bounds(231, 88, 320, 250), taller);
        assertEquals(initial.bottom(), taller.bottom());
        Bounds wider = initial.resized(RVP_TuningViewState.LEFT, -150, 0, 640, 338);
        assertEquals(new Bounds(81, 188, 470, 150), wider);
        assertEquals(initial.right(), wider.right());
    }

    @Test
    void draggedEdgesStopAtUsableMinimumAndViewportBoundary() {
        Bounds original = new Bounds(40, 20, 400, 250);
        Bounds shrunk = original.resized(RVP_TuningViewState.RIGHT | RVP_TuningViewState.BOTTOM, -1000, -1000, 640, 338);
        assertEquals(new Bounds(40, 20, 280, 110), shrunk);
        Bounds expanded = original.resized(RVP_TuningViewState.RIGHT | RVP_TuningViewState.BOTTOM, 1000, 1000, 640, 338);
        assertEquals(636, expanded.right());
        assertEquals(338, expanded.bottom());
        Bounds upperLeft = original.resized(RVP_TuningViewState.LEFT | RVP_TuningViewState.TOP, -1000, -1000, 640, 338);
        assertEquals(4, upperLeft.left());
        assertEquals(4, upperLeft.top());
        assertEquals(original.right(), upperLeft.right());
        assertEquals(original.bottom(), upperLeft.bottom());
    }

    @Test
    void guiScaleChangeKeepsRelativeBoundsAndSmallWindowStillFits() {
        RVP_TuningViewState state = new RVP_TuningViewState();
        state.remember(new Snapshot(new Bounds(40, 24, 510, 270), 640, 338, "screen_space", "", Set.of(), 0, 300));
        assertEquals(new Bounds(80, 48, 1020, 540), state.windowFor(1280, 676, new Bounds(0, 0, 320, 150)));
        Bounds compact = state.windowFor(200, 120, new Bounds(0, 0, 320, 150));
        assertTrue(compact.left() >= 4 && compact.top() >= 4);
        assertTrue(compact.right() <= 196 && compact.bottom() <= 120);
        assertTrue(compact.width() > 0 && compact.height() > 0);
    }

    @Test
    void resizeHitRegionsDoNotStealClicksInsideParameterArea() {
        Bounds window = new Bounds(40, 20, 400, 250);
        assertEquals(RVP_TuningViewState.LEFT, window.resizeEdges(41, 80));
        assertEquals(RVP_TuningViewState.TOP, window.resizeEdges(100, 21));
        assertEquals(RVP_TuningViewState.RIGHT | RVP_TuningViewState.BOTTOM, window.resizeEdges(434, 264));
        assertEquals(0, window.resizeEdges(220, 90));
        assertEquals(0, window.resizeEdges(435, 200), "右侧滚动条位置不应触发外边框缩放");
        assertEquals(0, window.resizeEdges(10, 10));
    }
}
