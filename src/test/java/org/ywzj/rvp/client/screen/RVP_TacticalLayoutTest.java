package org.ywzj.rvp.client.screen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 防止工具栏再次绑定地图右边缘，同时覆盖窄窗换行与可滚动列表的边界。 */
class RVP_TacticalLayoutTest {
    @Test
    void toolbarStaysAnchoredAtEveryGuiScaleAndSidebarState() {
        int[][] windows = {{640, 480}, {854, 480}, {1280, 720}, {1920, 1080}, {2560, 1080}, {1024, 768}};
        for (int[] window : windows) {
            for (int scale = 1; scale <= 6; scale++) {
                int w = (window[0] + scale - 1) / scale, h = (window[1] + scale - 1) / scale;
                if (w < 320 || h < 240) continue;
                var closed=RVP_TacticalLayout.of(w,h,false,false);
                for (boolean tool:new boolean[]{false,true}) {
                    var open=RVP_TacticalLayout.of(w,h,true,tool);
                    assertEquals(w,open.mapRight());
                    assertEquals(closed.mapTop(),open.mapTop());
                    assertEquals(closed.mapBottom(),open.mapBottom());
                    assertEquals(closed.titleRight(),open.titleRight());
                    assertTrue(open.sideBottom()<=open.mapBottom());
                    assertTrue(open.sideTop()>=open.mapTop());
                    assertTrue(open.sideRight()-open.sideLeft()>=120);
                    assertTrue(open.overDrawer(open.sideLeft()+1,open.sideTop()+1));
                    assertFalse(open.overDrawer(open.sideLeft()+1,open.sideBottom()+1));
                    assertFalse(closed.overDrawer(open.sideLeft()+1,open.sideTop()+1));
                    assertTrue(open.titleRight()+open.buttonHeight()*4+12<w);
                    if (w<=470&&!tool) assertTrue(open.sideTop()>open.mapTop());
                }
            }
        }
    }

    @Test
    void headerTabsShareTheirWidthAndLeaveRoomForTheFireAction() {
        for (int w : new int[]{320, 427, 512, 640, 960, 1280, 1920}) {
            for (int h : new int[]{240, 384, 540, 1080}) {
                var layout = RVP_TacticalLayout.of(w, h, true, false);
                int tab = layout.tabWidth();
                assertEquals(tab, layout.titleRight() - layout.margin());
                assertTrue(tab < Math.min(136, Math.max(65, w / 7)));
                assertEquals(layout.titleRight() + 2, layout.weaponTabX());
                assertEquals(layout.weaponTabX() + tab + 3, layout.fireX());
                int gpsLeft = w - layout.margin() - 3 * layout.buttonHeight() - 6;
                assertTrue(layout.fireX() + layout.fireWidth() < gpsLeft);
            }
        }
    }

    @Test
    void scaleLengthFitsItsLabelAcrossAllZooms() {
        for (double zoom:new double[]{.15,.2,.5,1.3,2,5,12,32}) {
            for (int pixels:new int[]{44,70,100}) {
                double length=RVP_TacticalLayout.rulerMeters(zoom,pixels)/zoom;
                assertTrue(length<=pixels+.00001 && length>=pixels*.39);
            }
        }
    }

    @Test
    void tankMarkersMatchTheReferenceAtDesktopAndCompactSizes() {
        assertEquals(54, 2 * RVP_TacticalLayout.trackedMarkerSize(960, 540));
        assertEquals(40, 2 * RVP_TacticalLayout.trackedMarkerSize(640, 360));
        assertEquals(20, RVP_TacticalLayout.trackedMarkerSize(320, 240));
        assertEquals(60, RVP_TacticalLayout.trackedMarkerSize(3840, 2160));
        assertEquals(RVP_TacticalLayout.trackedMarkerSize(960, 540), RVP_TacticalLayout.trackedMarkerSize(1280, 540));
    }

    @Test
    void scrollingAndClicksUseOnlyFullyVisibleRows() {
        var rows = new RVP_MapChrome.ListArea(30, 83, 16);
        assertEquals(3, rows.visible());
        assertEquals(17, rows.clamp(100, 20));
        assertEquals(0, rows.clamp(50, 2));
        assertEquals(17, rows.rowAt(30, 17, 20));
        assertEquals(19, rows.rowAt(77.99, 17, 20));
        assertEquals(-1, rows.rowAt(78, 17, 20));
        assertEquals(-1, rows.rowAt(29.9, 17, 20));
        assertEquals(-1, rows.rowAt(62, 0, 2));
    }
}
