package org.ywzj.rvp.client.tuning;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 仅保存本次客户端会话的窗口与浏览位置，不持有Screen、世界或特效参数。 */
public final class RVP_TuningViewState {
    /** 可拖动的左边框标记。 */
    public static final int LEFT = 1;
    /** 可拖动的右边框标记。 */
    public static final int RIGHT = 2;
    /** 可拖动的上边框标记。 */
    public static final int TOP = 4;
    /** 可拖动的下边框标记。 */
    public static final int BOTTOM = 8;
    /** 保证名称、滑条和数值框仍可操作的最小GUI宽度。 */
    private static final int MIN_WIDTH = 280;
    /** 保留标题、搜索和至少数行参数的最小GUI高度。 */
    private static final int MIN_HEIGHT = 110;
    /** 左右和顶部保留的屏幕边距，底边允许与参考图一样贴屏幕底部。 */
    private static final int MARGIN = 4;
    /** 上次界面离开或重建前的不可变浏览快照。 */
    private Snapshot saved;

    /** 窗口坐标统一使用Minecraft缩放后的GUI像素。 */
    public record Bounds(
            /** 左边界。 */ int left,
            /** 上边界。 */ int top,
            /** 宽度。 */ int width,
            /** 高度。 */ int height) {
        public int right() { return left + width; }
        public int bottom() { return top + height; }

        /** 检测四条边与右下角缩放手柄，不与内部的滚动条命中区域混用。 */
        public int resizeEdges(double x, double y) {
            if (x < left || x >= right() || y < top || y >= bottom()) { return 0; }
            if (x >= right() - 9 && y >= bottom() - 9) { return RIGHT | BOTTOM; }
            int edges = x < left + 4 ? LEFT : x >= right() - 4 ? RIGHT : 0;
            return edges | (y < top + 4 ? TOP : y >= bottom() - 4 ? BOTTOM : 0);
        }

        /** 限制窗口完全可见；屏幕小于最小尺寸时使用可用尺寸，不能把标题栏推出画面。 */
        public Bounds fit(int viewportWidth, int viewportHeight) {
            int availableWidth = Math.max(1, viewportWidth - MARGIN * 2);
            int availableHeight = Math.max(1, viewportHeight - MARGIN);
            int fittedWidth = clamp(width, Math.min(MIN_WIDTH, availableWidth), availableWidth);
            int fittedHeight = clamp(height, Math.min(MIN_HEIGHT, availableHeight), availableHeight);
            return new Bounds(clamp(left, MARGIN, viewportWidth - fittedWidth - MARGIN),
                    clamp(top, MARGIN, viewportHeight - fittedHeight), fittedWidth, fittedHeight);
        }

        /** 从按下时的矩形计算缩放，拖动一边时固定对边；支持向上扩展初始贴底的窗口。 */
        public Bounds resized(int edges, int deltaX, int deltaY, int viewportWidth, int viewportHeight) {
            // 调用统一可见性约束，分辨率变化后也以当前可操作窗口作为起点。
            Bounds base = fit(viewportWidth, viewportHeight);
            int minWidth = Math.min(MIN_WIDTH, Math.max(1, viewportWidth - MARGIN * 2));
            int minHeight = Math.min(MIN_HEIGHT, Math.max(1, viewportHeight - MARGIN));
            int left = base.left;
            int right = base.right();
            int top = base.top;
            int bottom = base.bottom();
            if ((edges & LEFT) != 0) { left = clamp(left + deltaX, MARGIN, right - minWidth); }
            else if ((edges & RIGHT) != 0) { right = clamp(right + deltaX, left + minWidth, viewportWidth - MARGIN); }
            if ((edges & TOP) != 0) { top = clamp(top + deltaY, MARGIN, bottom - minHeight); }
            else if ((edges & BOTTOM) != 0) { bottom = clamp(bottom + deltaY, top + minHeight, viewportHeight); }
            return new Bounds(left, top, right - left, bottom - top);
        }
    }

    /** 界面快照与参数预设分离，重新打开时不会撤销或覆盖已调整的业务参数。 */
    public record Snapshot(
            /** 上次窗口矩形。 */ Bounds bounds,
            /** 保存时的GUI屏幕宽度。 */ int viewportWidth,
            /** 保存时的GUI屏幕高度。 */ int viewportHeight,
            /** 正在查看的分类稳定键。 */ String selectedModule,
            /** 搜索文本。 */ String query,
            /** 已折叠的分组稳定键。 */ Set<String> collapsed,
            /** 左侧分类栏滚动位置。 */ double navigationScroll,
            /** 右侧参数栏滚动位置。 */ double parameterScroll) {
        public Snapshot {
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(selectedModule);
            Objects.requireNonNull(query);
            collapsed = Set.copyOf(collapsed);
            if (viewportWidth <= 0 || viewportHeight <= 0 || !Double.isFinite(navigationScroll)
                    || !Double.isFinite(parameterScroll) || navigationScroll < 0 || parameterScroll < 0) {
                throw new IllegalArgumentException("无效的调参界面快照");
            }
        }
    }

    public void remember(Snapshot snapshot) { saved = Objects.requireNonNull(snapshot); }
    public Optional<Snapshot> snapshot() { return Optional.ofNullable(saved); }

    /** 同一分辨率下精确恢复；GUI缩放或分辨率改变时按相对位置与尺寸换算并裁剪。 */
    public Bounds windowFor(int viewportWidth, int viewportHeight, Bounds defaults) {
        if (saved == null) { return defaults.fit(viewportWidth, viewportHeight); }
        double scaleX = viewportWidth / (double) saved.viewportWidth;
        double scaleY = viewportHeight / (double) saved.viewportHeight;
        Bounds old = saved.bounds;
        return new Bounds((int) Math.round(old.left * scaleX), (int) Math.round(old.top * scaleY),
                (int) Math.round(old.width * scaleX), (int) Math.round(old.height * scaleY)).fit(viewportWidth, viewportHeight);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(Math.max(min, max), value)); }
}
