package org.ywzj.rvp.client.gui;

/**
 * 记录本体武器栏（{@code VehicleWeaponOverlay}）本帧的分隔线纵坐标，供 RVP 右侧辅助 HUD 吸附定位。
 *
 * <p>本体 HUD 走 {@code VehicleHudLayout}（虚拟 480 高坐标系），武器栏从右下角
 * {@code MARGIN=18} 往上堆：分隔线 {@code separatorY = listTop - 3}，其上方是
 * 「弹药/装填读出区」（高度约 {@code DETAIL_HEIGHT=42}），下方是弹种行。
 * RVP 的过热文案原先用屏幕像素自己算位置，跟新 HUD 对不上 ⇒ 与读出区重叠。
 * 这里由 mixin 把本帧真实 {@code separatorY} 抓下来，过热文案直接顶在读出区上方。</p>
 */
public final class RVP_WeaponPanelMetrics {

    /** 本体武器栏分隔线的纵坐标（480 坐标系）；NaN = 本帧没抓到。 */
    private static float separatorY = Float.NaN;

    /** 武器栏面板左沿与宽度（取本帧最长的横向分隔线，即面板主轴）；NaN/0 = 未抓到。 */
    private static float panelLeft = Float.NaN;
    private static float panelWidth;

    /**
     * 弹药/装填读出区（面板上半部分）的上沿：由 {@code renderReloadArea} 每帧的
     * {@code top} 取最小值得到。读出区高度随副武器条数变化，固定按 42 算会压进去。
     */
    private static float readoutTop = Float.NaN;

    private RVP_WeaponPanelMetrics() {}

    /** 每帧入口调用：清空本帧记录，等待 mixin 重新抓取。 */
    public static void beginFrame() {
        separatorY = Float.NaN;
        panelLeft = Float.NaN;
        panelWidth = 0f;
        readoutTop = Float.NaN;
    }

    /** 只在首次非纵向分隔线时记录 y，后续内部细分隔线不覆盖。 */
    public static void captureSeparator(float y) {
        if (Float.isNaN(separatorY)) {
            separatorY = y;
        }
    }

    /**
     * 记录本帧最长的横向分隔线范围＝武器栏面板主轴（内部细分隔线更短，不会覆盖）。
     *
     * <p>过热条的宽度要跟面板等宽才有「长在弹药栏上」的观感，而面板宽度只存在于
     * {@code drawSeparator} 的局部变量里，故一并抓下来。</p>
     */
    public static void captureExtent(float x, float length) {
        if (length > panelWidth) {
            panelLeft = x;
            panelWidth = length;
        }
    }

    /** 记录读出区上沿（{@code renderReloadArea} 的 {@code top}）；取本帧最小值即整块读出区的顶。 */
    public static void captureReadoutTop(float top) {
        if (Float.isNaN(readoutTop) || top < readoutTop) {
            readoutTop = top;
        }
    }

    public static boolean hasReadoutTop() {
        return !Float.isNaN(readoutTop);
    }

    public static float readoutTop() {
        return readoutTop;
    }

    public static boolean hasSeparator() {
        return !Float.isNaN(separatorY);
    }

    public static float separatorY() {
        return separatorY;
    }

    /** 弹种列表的上沿（分隔线上方留一点间距）。 */
    public static float listTop() {
        return separatorY + 3f;
    }

    /** 本帧是否抓到了面板主轴范围。 */
    public static boolean hasExtent() {
        return !Float.isNaN(panelLeft) && panelWidth > 0f;
    }

    public static float panelLeft() {
        return panelLeft;
    }

    public static float panelWidth() {
        return panelWidth;
    }
}
