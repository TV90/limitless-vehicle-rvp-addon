package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 武器侧 CCIP 地面落点标记外观（BF6 风格圆环 + 竖直引线）。
 *
 * <p>仅在“地面载具的火箭弹 / 空中载具的航弹 + CCIP 传感器”时由
 * {@code RVP_CcipGroundMarkerOverlay} 消费；未配置该分组的武器走本类默认值。</p>
 *
 * <p>颜色格式为 {@code AARRGGBB} 十六进制串（可写 {@code "0xC800FF00"} / {@code "#C800FF00"}）；
 * 只写 6 位时自动补全不透明 alpha。</p>
 */
public class RVP_CcipMarkerData {

    /** 未配置 {@code ccip_marker_data} 时使用的默认外观（与旧硬编码数值一致）。 */
    public static final RVP_CcipMarkerData DEFAULT = new RVP_CcipMarkerData();

    /** 圆环半径 = 武器爆炸半径 × 本比例，默认 0.5（50%）。 */
    @SerializedName("radius_scale")
    private double radiusScale = 0.5D;

    /** 圆环/引线贴地高度相对落点地表的允许偏差，单位格，默认 2.5；超出时压平。 */
    @SerializedName("height_clamp")
    private double heightClamp = 2.5D;

    /** 圆环顶点相对地表的高度补偿，单位格，默认 0.08，避免与方块顶面 z-fighting。 */
    @SerializedName("surface_lift")
    private double surfaceLift = 0.08D;

    /** 远距圆环的软下限（占屏幕高度比例），默认 0.02；用平方和渐进托底，不破坏近大远小。 */
    @SerializedName("min_ring_screen_fraction")
    private double minRingScreenFraction = 0.02D;

    /** 圆环线宽随相机距离增长的系数（格/格），默认 0.008。 */
    @SerializedName("ring_width_per_block")
    private double ringWidthPerBlock = 0.008D;

    /** 圆环最小世界线宽，单位格，默认 0.12。 */
    @SerializedName("ring_min_width")
    private double ringMinWidth = 0.12D;

    /** 圆环最大世界线宽，单位格，默认 1.5。 */
    @SerializedName("ring_max_width")
    private double ringMaxWidth = 1.5D;

    /** 引线线宽随相机距离增长的系数（格/格），默认 0.0035；通常比圆环更细。 */
    @SerializedName("line_width_per_block")
    private double lineWidthPerBlock = 0.0035D;

    /** 引线最小世界线宽，单位格，默认 0.04。 */
    @SerializedName("line_min_width")
    private double lineMinWidth = 0.04D;

    /** 引线最大世界线宽，单位格，默认 0.5。 */
    @SerializedName("line_max_width")
    private double lineMaxWidth = 0.5D;

    /** 引线高度占屏幕高度的比例，默认 0.35；按 FOV 反算世界高度，远近等长。 */
    @SerializedName("line_height_screen_fraction")
    private double lineHeightScreenFraction = 0.35D;

    /** 引线最小世界高度，单位格，默认 4。 */
    @SerializedName("line_min_height")
    private double lineMinHeight = 4.0D;

    /** 引线最大世界高度，单位格，默认 400。 */
    @SerializedName("line_max_height")
    private double lineMaxHeight = 400.0D;

    /** 圆环颜色 AARRGGBB，默认 {@code 0xC800FF00}（半透明绿，与本体 CCIP 准星同色）。 */
    @SerializedName("ring_color")
    private String ringColor = "0xC800FF00";

    /** 引线底端颜色 AARRGGBB，默认 {@code 0xC800FF00}。 */
    @SerializedName("line_bottom_color")
    private String lineBottomColor = "0xC800FF00";

    /** 引线顶端颜色 AARRGGBB，默认 {@code 0x3000FF00}（渐隐到近透明）。 */
    @SerializedName("line_top_color")
    private String lineTopColor = "0x3000FF00";

    public double getRadiusScale() {
        return radiusScale;
    }

    public double getHeightClamp() {
        return heightClamp;
    }

    public double getSurfaceLift() {
        return surfaceLift;
    }

    public double getMinRingScreenFraction() {
        return minRingScreenFraction;
    }

    public double getRingWidthPerBlock() {
        return ringWidthPerBlock;
    }

    public double getRingMinWidth() {
        return ringMinWidth;
    }

    public double getRingMaxWidth() {
        return ringMaxWidth;
    }

    public double getLineWidthPerBlock() {
        return lineWidthPerBlock;
    }

    public double getLineMinWidth() {
        return lineMinWidth;
    }

    public double getLineMaxWidth() {
        return lineMaxWidth;
    }

    public double getLineHeightScreenFraction() {
        return lineHeightScreenFraction;
    }

    public double getLineMinHeight() {
        return lineMinHeight;
    }

    public double getLineMaxHeight() {
        return lineMaxHeight;
    }

    public int getRingColor() {
        return parseArgb(ringColor, 0xC800FF00);
    }

    public int getLineBottomColor() {
        return parseArgb(lineBottomColor, 0xC800FF00);
    }

    public int getLineTopColor() {
        return parseArgb(lineTopColor, 0x3000FF00);
    }

    /** 解析 AARRGGBB 十六进制颜色串；非法或未配置时返回 fallback。 */
    private static int parseArgb(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        try {
            long value = Long.parseLong(hex, 16);
            if (hex.length() <= 6) {
                value |= 0xFF000000L;
            }
            return (int) value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
