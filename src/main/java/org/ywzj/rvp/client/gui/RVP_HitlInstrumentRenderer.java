package org.ywzj.rvp.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Locale;

/** BF4 风格紧凑侧栏；缩写跨语言通用，单位保留，仅文字位置随视口适配，字号沿用游戏 GUI 设置。 */
public final class RVP_HitlInstrumentRenderer {
    private RVP_HitlInstrumentRenderer() { }

    /** 数值缺失或无穷大时显示横线，不用伪造距离、速度。 */
    private static String number(double value, String pattern, String unit) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, pattern, value) + unit : "--";
    }

    /** 消费既有飞行和观瞄数据；本部件不查询区块、修改制导或发送网络包。 */
    public static void draw(GuiGraphics graphics, Font font, double speed, double altitude, double range,
                            double seconds, double turnRate, String video, String control, String track, boolean airburst) {
        float width = graphics.guiWidth(), height = graphics.guiHeight();
        if (width <= 0 || height <= 0) return;
        // 调用成对读数绘制，侧栏远离中央操作区域，宽屏仍按视口边缘定位。
        pair(graphics, font, width * 0.16F, height * 0.43F, "SPD", number(speed, "%.0f", " km/h"));
        pair(graphics, font, width * 0.16F, height * 0.55F, "ALT", number(altitude, "%.0f", " m"));
        pair(graphics, font, width * 0.84F, height * 0.43F, "VID / CTL", video + " / " + control);
        pair(graphics, font, width * 0.84F, height * 0.55F, "TRK / TRN", track + " / " + number(turnRate, "%.1f", "°/s"));
        text(graphics, font, width * 0.5F, height * 0.78F, "RNG " + number(range, "%.0f", " m"));
        text(graphics, font, width * 0.5F, height * 0.78F + font.lineHeight + 3, "TOF " + number(seconds, "%.1f", " s"));
        if (airburst) text(graphics, font, width * 0.5F, height * 0.9F, "AIR / RMB");
    }
    /** 标签与数值上下排列，保留足够行距。 */
    private static void pair(GuiGraphics graphics, Font font, float x, float y, String label, String value) {
        text(graphics, font, x, y, label);
        text(graphics, font, x, y + font.lineHeight + 3, value);
    }
    /** 按原生 GUI 字体宽度居中，不再对字号做额外分辨率缩放。 */
    private static void text(GuiGraphics graphics, Font font, float x, float y, String value) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        graphics.drawString(font, value, -font.width(value) / 2, 0, RVP_HitlReticleRenderer.HUD_COLOR, true);
        pose.popPose();
    }
}
