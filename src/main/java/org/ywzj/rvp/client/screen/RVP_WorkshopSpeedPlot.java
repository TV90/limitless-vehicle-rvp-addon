package org.ywzj.rvp.client.screen;

import com.sighs.apricityui.element.Canvas;
import net.minecraft.client.resources.language.I18n;
import org.ywzj.rvp.entity.projectile.RVP_WorkshopTrajectory;
import org.ywzj.rvp.entity.projectile.RVP_WorkshopTrajectory.Curve;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/** 用 AUI 原生画布一次绘制选中曲线，滚动裁切、缩放和下拉层级由文档负责。 */
final class RVP_WorkshopSpeedPlot {
    private static final String KEY = "gui.ywzj_rvp.workshop.field.speed.";
    private static final Color INK = new Color(222, 229, 236), AMBER = new Color(238, 193, 124);
    /** 图内文字与档案正文接近，位图按窗口实际像素绘制。 */
    private static final Font LABEL_FONT = new Font(Font.DIALOG, Font.PLAIN, 14);

    static void draw(Canvas canvas, Curve curve, boolean missile, int width, int height, int pixelWidth, int pixelHeight) {
        var bitmap = canvas.getSurface();
        if (bitmap.getWidth() != pixelWidth || bitmap.getHeight() != pixelHeight) {
            // 新版 AUI 会把逻辑尺寸换算成设备像素；旧版需要直接指定光栅尺寸。
            canvas.setWidth(width);
            canvas.setHeight(height);
            bitmap = canvas.getSurface();
            if (bitmap.getWidth() == width && bitmap.getHeight() == height) {
                if (width != pixelWidth) canvas.setWidth(pixelWidth);
                if (height != pixelHeight) canvas.setHeight(pixelHeight);
            }
        }
        int rasterWidth = canvas.getSurface().getWidth(), rasterHeight = canvas.getSurface().getHeight();
        canvas.renderOperation(g -> {
            g.scale((double) rasterWidth / width, (double) rasterHeight / height);
            draw(g, curve, missile, width, height);
        });
    }

    private static void draw(Graphics2D g, Curve curve, boolean missile, int width, int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(30, 33, 43));
        g.fillRect(0, 0, width, height);
        g.setFont(LABEL_FONT);
        var metrics = g.getFontMetrics();
        double units = missile ? 72 : 20;
        var points = curve.points();
        double yStep = niceStep(Math.max(1, curve.peak() * units) / 4);
        double ymax = Math.max(yStep, Math.ceil(curve.peak() * units / yStep) * yStep);
        int left = Math.max(32, metrics.stringWidth(tickText(ymax)) + 8);
        int rangeLabelWidth = metrics.stringWidth(Integer.toString(RVP_WorkshopTrajectory.RANGE));
        int right = width - rangeLabelWidth / 2 - 3;
        int top = 12, bottom = height - metrics.getHeight() - 8;
        double xScale = (right - left) / (double) RVP_WorkshopTrajectory.RANGE, yScale = (bottom - top) / ymax;
        // 小窗口保留首尾和主要刻度，给各组数字留足可读宽度。
        int xStep = (right - left) / 6 >= rangeLabelWidth + 8 ? 250 : 500;
        for (int d = 0; d <= RVP_WorkshopTrajectory.RANGE; d += xStep) {
            int x = left + (int) Math.round(d * xScale);
            grid(g, x, top, x, bottom);
            String label = Integer.toString(d);
            g.drawString(label, x - metrics.stringWidth(label) / 2, bottom + metrics.getAscent() + 5);
        }
        for (double v = 0; v <= ymax + yStep * 1e-6; v += yStep) {
            int y = bottom - (int) Math.round(v * yScale);
            grid(g, left, y, right, y);
            String label = tickText(v);
            g.drawString(label, left - metrics.stringWidth(label) - 4, y + (metrics.getAscent() - metrics.getDescent()) / 2);
        }
        g.setColor(new Color(137, 146, 159));
        g.drawLine(left, top, left, bottom);
        g.drawLine(left, bottom, right, bottom);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        var line = new Path2D.Double();
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            if (i == 1 || (a.pulse() == 2) != (b.pulse() == 2)) {
                g.draw(line);
                g.setColor(b.pulse() == 2 ? AMBER : INK);
                line.reset();
                line.moveTo(left + a.distance() * xScale, bottom - a.speed() * units * yScale);
            }
            line.lineTo(left + b.distance() * xScale, bottom - b.speed() * units * yScale);
        }
        g.draw(line);
        // 两个轴标题均在轴线内侧，先为其占位，再避让相邻事件文字。
        var occupied = new ArrayList<Rectangle>();
        String speed = I18n.get(KEY + "axis") + (missile ? " (KPH)" : " (m/s)");
        String distance = I18n.get(KEY + "distance") + " (m)";
        int tagHeight = metrics.getHeight() + 2;
        tag(g, speed, left + 5, top + 3, INK, occupied);
        tag(g, distance, right - metrics.stringWidth(distance) - 8, bottom - tagHeight - 3, INK, occupied);
        g.setStroke(new BasicStroke(1));
        for (var mark : curve.marks()) {
            var p = points.get(mark.sample());
            int x = left + (int) Math.round(p.distance() * xScale);
            int y = bottom - (int) Math.round(p.speed() * units * yScale);
            boolean second = mark.key().endsWith("2");
            Color color = second ? AMBER : INK;
            String label = I18n.get(KEY + mark.key());
            int w = metrics.stringWidth(label) + 6;
            int tx = Math.max(left + 2, Math.min(right - w, x - w / 2));
            // 优先贴近事件，密集时移到有空间的一行，以引线指回实际采样点。
            for (int i = 0; i < 26; i++) {
                int dy = (i / 2) * tagHeight;
                int ty = i % 2 == 0 ? y + 6 + dy : y - tagHeight - 6 - dy;
                var box = new Rectangle(tx, ty, w, tagHeight);
                if (ty < top + 1 || ty + tagHeight > bottom || occupied.stream().anyMatch(box::intersects)) continue;
                g.setColor(color.darker());
                g.drawLine(x, y, Math.max(tx, Math.min(tx + w, x)), ty > y ? ty : ty + tagHeight);
                tag(g, label, tx, ty, color, occupied);
                break;
            }
            g.setColor(color);
            g.fillOval(x - 2, y - 2, 5, 5);
        }
    }

    private static void grid(Graphics2D g, int x, int y, int x2, int y2) {
        g.setColor(new Color(68, 74, 86));
        g.drawLine(x, y, x2, y2);
        g.setColor(INK);
    }

    private static void tag(Graphics2D g, String text, int x, int y, Color color, List<Rectangle> occupied) {
        var metrics = g.getFontMetrics();
        var box = new Rectangle(x, y, metrics.stringWidth(text) + 6, metrics.getHeight() + 2);
        occupied.add(box);
        g.setColor(new Color(30, 33, 43, 225));
        g.fillRect(x, y, box.width, box.height);
        g.setColor(color);
        g.drawString(text, x + 3, y + metrics.getAscent() + 1);
    }

    private static double niceStep(double value) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        double n = value / magnitude;
        return (n < 1.5 ? 1 : n < 2.25 ? 2 : n < 3.75 ? 2.5 : n < 7.5 ? 5 : 10) * magnitude;
    }

    private static String tickText(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(Math.rint(value * 10) / 10);
    }
}
