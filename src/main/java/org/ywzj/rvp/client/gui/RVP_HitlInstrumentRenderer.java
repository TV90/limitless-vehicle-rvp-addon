package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import org.ywzj.rvp.client.RVP_Keys;
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
    }

    /**
     * 操作提示：只翻译两条操作说明，其余仪表缩写保持英文以维持 HUD 风格。
     * R 提示仅电视制导（DESIGNATE）弹显示；右键提示所有 HITL 弹显示，空爆弹独立文案。
     * 提示位置使用视口比例并在底部中央居中，窄窗口自动缩小字号，避免溢出。
     */
    public static void drawActionHints(GuiGraphics graphics, Font font, boolean designate, boolean airburst,
                                       boolean urgentDesignate, boolean airburstNearby) {
        float width = graphics.guiWidth(), height = graphics.guiHeight();
        if (width <= 0 || height <= 0) return;
        String[] lines = new String[2];
        int[] colors = new int[2];
        int count = 0;
        if (designate) {
            lines[count] = I18n.get("overlay.ywzj_rvp.hitl.prompt.designate",
                    RVP_Keys.HITL_REDESIGNATE.getTranslatedKeyMessage().getString());
            boolean blinkOrange = urgentDesignate && Minecraft.getInstance().level != null
                    && (Minecraft.getInstance().level.getGameTime() / 5L) % 2L == 0L;
            colors[count++] = blinkOrange ? 0xFFFF8C00 : 0xFFFFFFFF;
        }
        lines[count] = I18n.get(airburst
                        ? "overlay.ywzj_rvp.hitl.prompt.airburst_exit"
                        : "overlay.ywzj_rvp.hitl.prompt.exit",
                RVP_Keys.HITL_EXIT.getTranslatedKeyMessage().getString());
        // 空爆弹周围 10 米存在非自身实体时变红，提醒玩家可以直接引爆。
        colors[count++] = airburst && airburstNearby ? 0xFFFF4040 : 0xFFFFFFFF;

        // 顶部右侧空白区，右对齐；无底板，沿用 HUD 的纯文字+阴影风格。
        float scale = Math.max(0.8F, Math.min(1.05F, width / 960.0F));
        float lineStep = font.lineHeight * scale + 6.0F;
        float right = width * 0.97F;
        float y = height * 0.075F;
        for (int i = 0; i < count; i++) {
            prompt(graphics, font, right, y, scale, lines[i], colors[i]);
            y += lineStep;
        }
    }

    /** 单条操作提示：右上角右对齐，仅文字和阴影，不加黑底或色条。 */
    private static void prompt(GuiGraphics graphics, Font font, float right, float y, float scale,
                               String value, int color) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(right, y, 0);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(font, value, -font.width(value), 0, color, true);
        pose.popPose();
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
