package org.ywzj.rvp.client.firesupport;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.screen.tool.RVP_TacticalMapHost;
import org.ywzj.rvp.firesupport.RVP_FireSupportPatternTypes;

import java.util.LinkedHashMap;
import java.util.Map;

/** 客户端落区预览注册表；按几何类型扩展，不在 Screen 中按预设 ID 分支。 */
public final class RVP_FireSupportPreviewTypes {
    /** 初始化阶段可写的预览表。 */ private static final Map<ResourceLocation, Preview> MUTABLE = new LinkedHashMap<>();
    /** 初始化完成后的不可变预览表。 */ private static final Map<ResourceLocation, Preview> PREVIEWS;
    /** 预览主色。 */ private static final int PREVIEW_COLOR = 0xFFE7B85C;
    /** 圆形预览线段数。 */ private static final int CIRCLE_SEGMENTS = 36;

    static {
        register(RVP_FireSupportPatternTypes.POINT, RVP_FireSupportPreviewTypes::renderPoint);
        register(RVP_FireSupportPatternTypes.LINE, RVP_FireSupportPreviewTypes::renderLine);
        register(RVP_FireSupportPatternTypes.CREEPING, RVP_FireSupportPreviewTypes::renderCreeping);
        PREVIEWS = Map.copyOf(MUTABLE);
    }

    private RVP_FireSupportPreviewTypes() {}

    /** @return 已注册预览；未知扩展类型返回 null 并由工具显示诊断状态。 */
    public static Preview get(ResourceLocation type) {
        return PREVIEWS.get(type);
    }

    private static void register(ResourceLocation type, Preview preview) {
        if (MUTABLE.putIfAbsent(type, preview) != null) throw new IllegalStateException("重复炮火预览类型 " + type);
    }

    private static void renderPoint(RVP_TacticalMapHost host, GuiGraphics graphics, Draft draft) {
        double radius = draft.parameter("radius_m");
        double previousX = draft.anchorX();
        double previousZ = draft.anchorZ() + radius;
        for (int index = 1; index <= CIRCLE_SEGMENTS; index++) {
            double angle = Math.PI * 2.0 * index / CIRCLE_SEGMENTS;
            double x = draft.anchorX() + Math.sin(angle) * radius;
            double z = draft.anchorZ() + Math.cos(angle) * radius;
            host.drawWorldLine(graphics, previousX, previousZ, x, z, PREVIEW_COLOR);
            previousX = x;
            previousZ = z;
        }
        drawCross(host, graphics, draft.anchorX(), draft.anchorZ());
    }

    private static void renderLine(RVP_TacticalMapHost host, GuiGraphics graphics, Draft draft) {
        renderRectangle(host, graphics, draft, true);
    }

    private static void renderCreeping(RVP_TacticalMapHost host, GuiGraphics graphics, Draft draft) {
        renderRectangle(host, graphics, draft, false);
        double length = draft.parameter("length_m");
        double step = Math.max(0.001, draft.parameter("step_m"));
        double heading = Math.toRadians(draft.headingDegrees());
        double sin = Math.sin(heading);
        double cos = Math.cos(heading);
        double width = draft.parameter("width_m");
        int stepCount = Math.min(64, Math.max(1, (int) Math.floor(length / step)));
        for (int index = 0; index <= stepCount; index++) {
            double along = Math.min(length, index * step);
            double cx = draft.anchorX() + sin * along;
            double cz = draft.anchorZ() + cos * along;
            host.drawWorldLine(graphics, cx - cos * width * 0.5, cz + sin * width * 0.5,
                    cx + cos * width * 0.5, cz - sin * width * 0.5, 0x99E7B85C);
        }
        drawArrow(host, graphics, draft.anchorX() + sin * length, draft.anchorZ() + cos * length,
                draft.headingDegrees());
    }

    private static void renderRectangle(RVP_TacticalMapHost host, GuiGraphics graphics, Draft draft, boolean centered) {
        double length = draft.parameter("length_m");
        double width = draft.parameter("width_m");
        double heading = Math.toRadians(draft.headingDegrees());
        double sin = Math.sin(heading);
        double cos = Math.cos(heading);
        double centerAlong = centered ? 0.0 : length * 0.5;
        double cx = draft.anchorX() + sin * centerAlong;
        double cz = draft.anchorZ() + cos * centerAlong;
        double halfLength = length * 0.5;
        double halfWidth = width * 0.5;
        double[] xs = {cx - sin * halfLength - cos * halfWidth, cx + sin * halfLength - cos * halfWidth,
                cx + sin * halfLength + cos * halfWidth, cx - sin * halfLength + cos * halfWidth};
        double[] zs = {cz - cos * halfLength + sin * halfWidth, cz + cos * halfLength + sin * halfWidth,
                cz + cos * halfLength - sin * halfWidth, cz - cos * halfLength - sin * halfWidth};
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            host.drawWorldLine(graphics, xs[index], zs[index], xs[next], zs[next], PREVIEW_COLOR);
        }
        drawCross(host, graphics, draft.anchorX(), draft.anchorZ());
        double handleAlong = centered ? halfLength : length;
        drawHandle(graphics, host.worldToScreenX(draft.anchorX() + sin * handleAlong),
                host.worldToScreenY(draft.anchorZ() + cos * handleAlong));
    }

    private static void drawArrow(RVP_TacticalMapHost host, GuiGraphics graphics, double x, double z, double headingDegrees) {
        double heading = Math.toRadians(headingDegrees);
        double size = Math.max(4.0, host.blocksPerPixel() * 8.0);
        host.drawWorldLine(graphics, x, z, x + Math.sin(heading + 2.55) * size,
                z + Math.cos(heading + 2.55) * size, PREVIEW_COLOR);
        host.drawWorldLine(graphics, x, z, x + Math.sin(heading - 2.55) * size,
                z + Math.cos(heading - 2.55) * size, PREVIEW_COLOR);
        drawHandle(graphics, host.worldToScreenX(x), host.worldToScreenY(z));
    }

    private static void drawCross(RVP_TacticalMapHost host, GuiGraphics graphics, double x, double z) {
        double size = host.blocksPerPixel() * 5.0;
        host.drawWorldLine(graphics, x - size, z, x + size, z, PREVIEW_COLOR);
        host.drawWorldLine(graphics, x, z - size, x, z + size, PREVIEW_COLOR);
    }

    private static void drawHandle(GuiGraphics graphics, double x, double y) {
        int sx = (int) Math.round(x);
        int sy = (int) Math.round(y);
        graphics.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFFE7B85C);
        graphics.fill(sx - 1, sy - 1, sx + 2, sy + 2, 0xFF17202A);
    }

    /** 一种类型化预览绘制器。 */
    @FunctionalInterface
    public interface Preview {
        /** 绘制已经乘过射击模式散布倍率的客户端非权威落区。 */
        void render(RVP_TacticalMapHost host, GuiGraphics graphics, Draft draft);
    }

    /** 预览绘制所需的不可变草稿。 */
    public record Draft(
            /** 锚点世界 X。 */ double anchorX,
            /** 锚点世界 Z。 */ double anchorZ,
            /** 长轴/徐进方向，单位度。 */ double headingDegrees,
            /** 已乘模式倍率的几何参数。 */ Map<String, Double> parameters) {
        public Draft { parameters = Map.copyOf(parameters); }
        /** 读取已校验存在的几何参数。 */ public double parameter(String key) { return parameters.getOrDefault(key, 0.0); }
    }
}
