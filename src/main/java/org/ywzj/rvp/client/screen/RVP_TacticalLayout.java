package org.ywzj.rvp.client.screen;

/** 地图投影固定在窗口中央；抽屉、导航和标尺共用同一套 GUI 像素坐标。 */
record RVP_TacticalLayout(int margin, int buttonHeight, int mapTop, int mapBottom, int mapRight,
                          int tabWidth, int sideLeft, int sideRight, int sideTop, int sideBottom) {
    static RVP_TacticalLayout of(int width, int height, boolean sidebar, boolean tool, int titleWidth) {
        int gap = Math.max(4, Math.min(14, Math.min(width, height) / 52));
        int row = Math.max(19, Math.min(32, height / 21));
        int top = 2 * gap + row, bottom = height - top;
        int right = width - gap;
        boolean bottomSheet = width <= 470 && !tool;
        int cardWidth = bottomSheet ? width - 2 * gap : Math.min(330, Math.max(145, width * 31 / 100));
        int cardHeight = tool ? bottom - top : bottomSheet ? Math.min(128, (bottom-top)*54/100)
                : Math.min(bottom - top, row * 6 + 38);
        int panelTop = bottomSheet ? bottom - cardHeight : top;
        // 页签至少容纳当前语言的标题，为两侧留出与按钮一致的空间。
        int tabWidth = Math.max(Math.max(54, Math.min(92, width / 11)), titleWidth + 14);
        return new RVP_TacticalLayout(gap, row, top, bottom, width, tabWidth,
                sidebar ? right-cardWidth : right, right, panelTop, panelTop + cardHeight);
    }

    /** 两个页签共用宽度与基线，发射按钮紧接武器页签。 */
    int titleRight() { return margin + tabWidth; }
    int weaponTabX() { return titleRight() + 2; }
    int fireX() { return weaponTabX() + tabWidth + 3; }
    int fireWidth() { return 2 * buttonHeight; }

    /** 地形缩放与抽屉状态均不影响标记的屏幕尺寸。 */
    static int trackedMarkerSize(int width, int height) {
        return Math.max(20, Math.min(60, Math.round(Math.min(width, height) * .05f)));
    }

    /** 用 1、2、5 系列的真实米数画标尺，标签与端点的世界距离相同。 */
    static double rulerMeters(double blocksPerPixel, int maxPixels) {
        double budget = blocksPerPixel * maxPixels;
        double decade = Math.pow(10, Math.floor(Math.log10(budget)));
        double fraction = budget / decade;
        return (fraction >= 5 ? 5 : fraction >= 2 ? 2 : 1) * decade;
    }

    boolean overDrawer(double x, double y) {
        return x >= sideLeft && x < sideRight && y >= sideTop && y < sideBottom;
    }
}
