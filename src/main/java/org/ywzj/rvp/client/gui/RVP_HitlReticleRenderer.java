package org.ywzj.rvp.client.gui;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import org.ywzj.vehicle.client.render.util.GuiHelper;

/** TV 弹的纯绘制部件；装饰圆环不参与瞄准投影、离轴限位或目标指定。 */
public final class RVP_HitlReticleRenderer {
    /** 信息文字与操作准星的白色，保持彩色、黑白和热成像模式下的统一外观。 */
    public static final int HUD_COLOR = 0xFFFFFFFF;
    /** 固定参照略透明，避免大圆和长十字压过目标细节。 */
    private static final int REFERENCE_COLOR = 0xD9FFFFFF;
    /** 参考录像的逻辑画布宽度，配合高度保持不同窗口比例下的圆形。 */
    private static final float REFERENCE_WIDTH = 1920.0F;
    /** 参考录像的逻辑画布高度；所有线条尺寸随视口统一缩放。 */
    private static final float REFERENCE_HEIGHT = 1080.0F;
    /** 装饰圆半径，参考画布像素；不表示导引头角度或允许操作范围。 */
    private static final float RING_RADIUS = 125.0F;
    /** 长十字的水平半长，参考画布像素。 */
    private static final int HORIZONTAL_ARM = 342;
    /** 长十字的垂直半长，参考画布像素。 */
    private static final int VERTICAL_ARM = 151;
    /** 固定长十字中心留白，为回中的菱形准星留出空间。 */
    private static final int CENTER_GAP = 32;
    /** 参考画布上的描线宽度，经过 pose 缩放后仍保持细线比例。 */
    private static final int STROKE = 2;
    /** 可动菱形的顶点到中心距离，参考画布像素。 */
    private static final int DIAMOND_RADIUS = 30;
    /** 菱形每个角沿两边延伸的分量，边中间留小缺口。 */
    private static final int DIAMOND_CORNER = 13;
    /** 可动准星内部短十字的半长，参考画布像素。 */
    private static final int AIM_CROSS_HALF = 14;
    /** 已指定目标使用方形四角框，与操作用菱形区分。 */
    private static final int TARGET_HALF = 22;
    /** 已指定目标框的每条角边长度，参考画布像素。 */
    private static final int TARGET_CORNER = 10;

    /** TV 四角框的半边长，参考录像约为画面宽度的 5%，与指令线菱形区别。 */
    private static final int TV_HALF = 50;
    /** TV 每个角的短边长度，中央留给实际目标。 */
    private static final int TV_CORNER = 27;
    /** TV 断开十字的半长与中心缺口，避免实心交叉挡住小目标。 */
    private static final int TV_CROSS = 42, TV_GAP = 10;

    private RVP_HitlReticleRenderer() { }

    /** 弹轴中心只保留轻微短刻线，显示离轴参照；TV 不再使用装饰大圆及长十字。 */
    public static void drawTvReference(GuiGraphics graphics) {
        float scale = scale(graphics);
        if (scale <= 0) return;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() * 0.5, graphics.guiHeight() * 0.5, 0);
        pose.scale(scale, scale, 1);
        graphics.fill(-3, -1, 3, 1, 0x80FFFFFF);
        graphics.fill(-1, -3, 1, 3, 0x80FFFFFF);
        pose.popPose();
    }

    /** TV 独有四角框及断开短十字；位置由真实瞄准点投影传入，不改变制导/离轴角度。 */
    public static void drawTvAim(GuiGraphics graphics, double x, double y) {
        float scale = scale(graphics);
        if (scale <= 0 || !Double.isFinite(x) || !Double.isFinite(y)) return;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);
        for (int sign = -1; sign <= 1; sign += 2) {
            int edge = sign < 0 ? -TV_HALF : TV_HALF - STROKE;
            graphics.fill(-TV_HALF, edge, -TV_HALF + TV_CORNER, edge + STROKE, HUD_COLOR);
            graphics.fill(TV_HALF - TV_CORNER, edge, TV_HALF, edge + STROKE, HUD_COLOR);
            graphics.fill(edge, -TV_HALF, edge + STROKE, -TV_HALF + TV_CORNER, HUD_COLOR);
            graphics.fill(edge, TV_HALF - TV_CORNER, edge + STROKE, TV_HALF, HUD_COLOR);
        }
        graphics.fill(-TV_CROSS, -1, -TV_GAP, 1, HUD_COLOR);
        graphics.fill(TV_GAP, -1, TV_CROSS, 1, HUD_COLOR);
        graphics.fill(-1, -TV_CROSS, 1, -TV_GAP, HUD_COLOR);
        graphics.fill(-1, TV_GAP, 1, TV_CROSS, HUD_COLOR);
        pose.popPose();
    }

    /** 按实际 GUI 视口缩放，抵消 GUI scale，且不随鼠标偏移改变装饰尺寸。 */
    public static float scale(GuiGraphics graphics) {
        return Math.min(graphics.guiWidth() / REFERENCE_WIDTH, graphics.guiHeight() / REFERENCE_HEIGHT);
    }

    /** 固定居中的装饰层；圆环复用本体已有圆形 Shader，只增加一个圆形绘制。 */
    public static void drawReference(GuiGraphics graphics) {
        float scale = scale(graphics);
        if (scale <= 0.0F) return;
        // 调用 GUI flush，先提交已有批次，再调用本体立即绘制的圆环，保持绘制顺序。
        graphics.flush();
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() * 0.5F, graphics.guiHeight() * 0.5F, 0);
        pose.scale(scale, scale, 1.0F);
        // 调用本体圆形工具，环宽按半径比例给定；不新增贴图或 Shader 资源。
        GuiHelper.drawCircle(pose, 0, 0, RING_RADIUS, REFERENCE_COLOR, STROKE / RING_RADIUS, 0.0F, 1.0F);
        graphics.fill(-HORIZONTAL_ARM, -1, -CENTER_GAP, 1, REFERENCE_COLOR);
        graphics.fill(CENTER_GAP, -1, HORIZONTAL_ARM, 1, REFERENCE_COLOR);
        graphics.fill(-1, -VERTICAL_ARM, 1, -CENTER_GAP, REFERENCE_COLOR);
        graphics.fill(-1, CENTER_GAP, 1, VERTICAL_ARM, REFERENCE_COLOR);
        pose.popPose();
    }

    /** 在调用方给出的真实投影位置绘制操作准星，允许其自由离开装饰圆。 */
    public static void drawAim(GuiGraphics graphics, double x, double y) {
        float scale = scale(graphics);
        if (scale <= 0.0F || !Double.isFinite(x) || !Double.isFinite(y)) return;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1.0F);
        // 调用共用线段描绘八条角边，菱形和内部十字共用相同的世界瞄准点。
        for (int sign = -1; sign <= 1; sign += 2) {
            line(graphics, 0, -DIAMOND_RADIUS, sign * DIAMOND_CORNER, -DIAMOND_RADIUS + DIAMOND_CORNER);
            line(graphics, 0, DIAMOND_RADIUS, sign * DIAMOND_CORNER, DIAMOND_RADIUS - DIAMOND_CORNER);
            line(graphics, -DIAMOND_RADIUS, 0, -DIAMOND_RADIUS + DIAMOND_CORNER, sign * DIAMOND_CORNER);
            line(graphics, DIAMOND_RADIUS, 0, DIAMOND_RADIUS - DIAMOND_CORNER, sign * DIAMOND_CORNER);
        }
        graphics.fill(-AIM_CROSS_HALF, -1, AIM_CROSS_HALF, 1, HUD_COLOR);
        graphics.fill(-1, -AIM_CROSS_HALF, 1, AIM_CROSS_HALF, HUD_COLOR);
        pose.popPose();
    }

    /** 独立的已指定目标角框，仅消费既有目标投影，不修改目标选择状态。 */
    public static void drawDesignation(GuiGraphics graphics, double x, double y) {
        float scale = scale(graphics);
        if (scale <= 0.0F || !Double.isFinite(x) || !Double.isFinite(y)) return;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1.0F);
        for (int sign = -1; sign <= 1; sign += 2) {
            int top = sign < 0 ? -TARGET_HALF : TARGET_HALF - STROKE;
            graphics.fill(-TARGET_HALF, top, -TARGET_HALF + TARGET_CORNER, top + STROKE, HUD_COLOR);
            graphics.fill(TARGET_HALF - TARGET_CORNER, top, TARGET_HALF, top + STROKE, HUD_COLOR);
            int left = sign < 0 ? -TARGET_HALF : TARGET_HALF - STROKE;
            graphics.fill(left, -TARGET_HALF, left + STROKE, -TARGET_HALF + TARGET_CORNER, HUD_COLOR);
            graphics.fill(left, TARGET_HALF - TARGET_CORNER, left + STROKE, TARGET_HALF, HUD_COLOR);
        }
        pose.popPose();
    }

    /** 距离标签放在圆环下方，避免覆盖回中准星。 */
    public static int distanceLabelOffset(GuiGraphics graphics) {
        return Math.round((RING_RADIUS + 28.0F) * scale(graphics));
    }

    /** 任意角度线段走原版 GUI 材质，沿用 GuiGraphics 的攒批与状态管理。 */
    private static void line(GuiGraphics graphics, float x0, float y0, float x1, float y1) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x0, y0, 0);
        pose.mulPose(Axis.ZP.rotation((float) Math.atan2(y1 - y0, x1 - x0)));
        graphics.fill(0, -1, Math.round((float) Math.hypot(x1 - x0, y1 - y0)), 1, HUD_COLOR);
        pose.popPose();
    }
}
