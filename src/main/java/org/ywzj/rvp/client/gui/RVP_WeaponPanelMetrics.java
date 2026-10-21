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

    private static boolean capturedThisFrame;

    private RVP_WeaponPanelMetrics() {}

    /** 每帧入口调用：允许本帧重新抓取一次（取第一次非纵向分隔线=武器栏主分隔线）。 */
    public static void beginFrame() {
        capturedThisFrame = false;
    }

    /** 只在首次非纵向分隔线时记录，后续内部细分隔线不覆盖。 */
    public static void captureSeparator(float y) {
        if (capturedThisFrame) {
            return;
        }
        capturedThisFrame = true;
        separatorY = y;
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
}
