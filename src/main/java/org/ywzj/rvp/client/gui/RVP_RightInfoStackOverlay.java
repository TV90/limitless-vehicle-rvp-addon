package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.ahead.RVP_AheadSolution;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.rvp.weapon.util.RVP_WeaponLeafResolver;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹药栏上方的右侧信息栈：**过热（文案 + 条）→ AHEAD 装订 → 定距空爆装订**，自下往上叠。
 *
 * <p><b>画风</b>：沿用本体 {@code VehicleHudLayout}（虚拟 480 高坐标系）与武器栏排版语言——
 * 右对齐到武器栏右边距 {@code MARGIN=18}、**正文字号与武器栏弹种行同为 1.0 缩放**、
 * 白 {@code 0xFFF2F2F2} / 灰 {@code 0xFFB8B8B8}、告警沿用弹药栏橙 {@code 0xFFFFA33D} / 红 {@code 0xFFFF4A3D}，
 * 不画自带背景框。</p>
 *
 * <p><b>过热条</b>：本体换弹条是 {@code RenderHelper.fill} 从左往右长出来的半透明灰块
 * （{@code VehicleWeaponOverlay.renderReloadArea}），这里照同一手法做一条 2px 细白条，
 * 长度＝面板宽度（{@code RVP_WeaponPanelMetrics} 抓到的分隔线主轴），填充比例＝热量比例，
 * 并做逐帧插值（涨得快、回落慢）而不是硬跳。</p>
 *
 * <p><b>层叠位置</b>：第一块（过热条）贴在本帧读出区上沿之上，随后逐行往上叠；
 * 因此：过热贴弹药栏、AHEAD 贴过热、定距贴 AHEAD。</p>
 */
public class RVP_RightInfoStackOverlay implements IGuiOverlay {

    /** 与本体武器栏一致的右边距（VehicleWeaponOverlay.MARGIN）。 */
    private static final float MARGIN_RIGHT = 18f;
    /** 本体弹药/装填读出区基准高度（VehicleWeaponOverlay.DETAIL_HEIGHT），仅作抓取失败时的回退。 */
    private static final float READOUT_HEIGHT = 42f;
    /** 行高（480 坐标系）。 */
    private static final float ROW_HEIGHT = 16f;
    /**
     * 正文字号目标：1.4 倍弹种行（弹种行 scale=1.0、读出区名称 scale=1.1），
     * 明显大于前两版的 0.75 / 1.0；超宽时按面板宽度自动缩（见 {@link #resolveTextScale}）。
     */
    private static final float TARGET_TEXT_SCALE = 1.4f;
    /** 正文字号下限，避免自动缩过头。 */
    private static final float MIN_TEXT_SCALE = 0.9f;
    /** 过热条高度（本体分隔线约 1px，这里取 2px：比换弹条细一倍）。 */
    private static final float BAR_HEIGHT = 2f;
    /** 过热条与上方文案的间隙。 */
    private static final float BAR_TEXT_GAP = 1f;
    /** 信息栈与读出区上沿的间隙。 */
    private static final float GAP_ABOVE_READOUT = 3f;
    /** 抓不到面板宽度时的回退宽度（VehicleWeaponOverlay.PANEL_WIDTH）。 */
    private static final float FALLBACK_PANEL_WIDTH = 168f;

    private static final int WHITE = 0xFFF2F2F2;
    private static final int GRAY = 0xFFB8B8B8;
    private static final int ORANGE = 0xFFFFA33D;
    private static final int RED = 0xFFFF4A3D;
    /** 过热条底槽：淡白，用来标出“满格”范围。 */
    private static final int BAR_TRACK = 0x33FFFFFF;

    /** 过热条显示比例（插值用；涨得快、退得慢，避免每 tick 跳动）。 */
    private static float heatDisplayed;

    /** AHEAD 解算结果由 {@link RVP_MachinegunLeadOverlay} 每帧上报，避免重复跑弹道解算。 */
    private static long aheadReportedAtMillis;
    /** 当前武器是否属于 AHEAD 类（false=不显示 AHEAD 行，与“AHEAD 未装订”区分开）。 */
    private static boolean aheadApplicable;
    private static boolean aheadValid;
    private static int aheadDistanceMeters;

    private RVP_RightInfoStackOverlay() {}

    public static RVP_RightInfoStackOverlay create() {
        return new RVP_RightInfoStackOverlay();
    }

    /** 供机炮预瞄 overlay 每帧上报 AHEAD 解算结果（null = 当前武器不是 AHEAD 类）。 */
    public static void reportAhead(@Nullable RVP_AheadSolution solution) {
        aheadReportedAtMillis = System.currentTimeMillis();
        aheadApplicable = solution != null;
        aheadValid = solution != null && solution.isValid();
        aheadDistanceMeters = solution != null ? solution.programmedDistanceMeters() : 0;
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || !lvp.onVehicle()) {
            heatDisplayed = 0f;
            return;
        }
        AbstractVehicle vehicle = lvp.vehicle;
        WeaponUnit weaponUnit = lvp.getWeaponUnit();
        if (vehicle == null || weaponUnit == null) {
            heatDisplayed = 0f;
            return;
        }

        // 解到叶子武器（分组槽/多弹种槽下 getCurrentWeapon() 是外层 multi）
        AbstractVehicleWeapon<?> leaf = RVP_WeaponLeafResolver.resolveLeaf(weaponUnit).orElse(null);
        if (!(leaf instanceof RVP_WeaponBase weapon)) {
            return;
        }

        float heatRatio = weapon.getFireController().hasHeat() ? weapon.getFireController().heatRatio() : 0f;
        heatDisplayed = Mth.lerp(0.35f, heatDisplayed, Mth.clamp(heatRatio, 0f, 1f));
        if (heatRatio <= 0.001f && heatDisplayed < 0.01f) {
            heatDisplayed = 0f;
        }

        List<String> texts = new ArrayList<>(3);
        List<Integer> colors = new ArrayList<>(3);

        // ① 过热（最贴近弹药栏，正上方跟一条过热条）
        boolean showHeat = weapon.getFireController().hasHeat();
        if (showHeat) {
            int percent = Math.max(0, Math.round(heatRatio * 100f));
            texts.add(Component.translatable("overlay.ywzj_rvp.heat", percent).getString());
            colors.add(heatColor(heatRatio));
        }

        // ② AHEAD 装订（仅 AHEAD 类武器；解算结果由机炮预瞄 overlay 上报，100ms 内视为有效帧）
        boolean aheadFresh = System.currentTimeMillis() - aheadReportedAtMillis < 100L;
        boolean showAhead = aheadApplicable && aheadFresh;
        if (showAhead) {
            texts.add(aheadValid
                    ? Component.translatable("overlay.ywzj_rvp.ahead.range", aheadDistanceMeters).getString()
                    : Component.translatable("overlay.ywzj_rvp.ahead.pending").getString());
            colors.add(aheadValid ? WHITE : GRAY);
        }

        // ③ 定距空爆装订（AHEAD 类武器已由上一行表达同一枚引信，不重复显示；数值取自客户端装订存储）
        RVP_FuseData fuse = weapon.getData().getFuseData();
        if (!showAhead && fuse.isProgrammableAirburst()) {
            int distance = RVP_AirburstRangeStore.get(vehicle, weaponUnit, weapon.getIndex());
            texts.add(distance > 0
                    ? Component.translatable("overlay.ywzj_rvp.airburst.range", distance).getString()
                    : Component.translatable("overlay.ywzj_rvp.airburst.pending").getString());
            colors.add(distance > 0 ? WHITE : GRAY);
        }

        if (texts.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        try (VehicleHudLayout layout = VehicleHudLayout.begin(guiGraphics)) {
            float right = layout.width() - MARGIN_RIGHT;
            float cursor = resolveBottom();
            if (showHeat) {
                drawHeatBar(guiGraphics, right, cursor, heatRatio);
                cursor -= BAR_HEIGHT + BAR_TEXT_GAP;
            }
            for (int i = 0; i < texts.size(); i++) {
                drawRightAligned(guiGraphics, font, texts.get(i), right, cursor, colors.get(i));
                cursor -= ROW_HEIGHT;
            }        }
    }

    /** 第一块（过热条）的底边：优先贴在本帧武器栏读出区上沿，抓不到才用回退位置。 */
    private static float resolveBottom() {
        if (RVP_WeaponPanelMetrics.hasReadoutTop()) {
            return RVP_WeaponPanelMetrics.readoutTop() - GAP_ABOVE_READOUT;
        }
        if (RVP_WeaponPanelMetrics.hasSeparator()) {
            return RVP_WeaponPanelMetrics.separatorY() - 3f - READOUT_HEIGHT - GAP_ABOVE_READOUT;
        }
        return VehicleHudLayout.HEIGHT - 18f - 96f;
    }

    /**
     * 过热条：底槽 + 白色填充（从左往右生长，长度＝热量比例）。
     *
     * <p>与本体换弹条同一条渲染管线（{@code RenderHelper.fill} + {@code RenderType.gui()}），
     * 只是更细。宽度优先取武器栏面板主轴宽度，抓不到时退回右对齐的固定宽度。</p>
     */
    private static void drawHeatBar(GuiGraphics guiGraphics, float fallbackRight, float bottom, float heatRatio) {
        float barRight = fallbackRight;
        float barLeft = fallbackRight - FALLBACK_PANEL_WIDTH;
        if (RVP_WeaponPanelMetrics.hasExtent()) {
            barLeft = RVP_WeaponPanelMetrics.panelLeft();
            barRight = barLeft + RVP_WeaponPanelMetrics.panelWidth();
        }
        float top = bottom - BAR_HEIGHT;
        RenderHelper.fill(guiGraphics, RenderType.gui(), barLeft, top, barRight, bottom, 0, BAR_TRACK);
        int fillColor = heatRatio >= 1.0f ? RED : WHITE;
        float filledRight = barLeft + (barRight - barLeft) * heatDisplayed;
        if (filledRight > barLeft) {
            RenderHelper.fill(guiGraphics, RenderType.gui(), barLeft, top, filledRight, bottom, 0, fillColor);
        }
    }

    /**
     * 右对齐绘制：默认 {@link #TARGET_TEXT_SCALE} 倍；文本宽出面板时按可用宽度自动缩，
     * 但不会低于 {@link #MIN_TEXT_SCALE}。三行用同一档缩放，视觉上整齐。
     */
    private static void drawRightAligned(GuiGraphics guiGraphics, Font font, String text,
                                         float right, float bottom, int color) {
        float scale = resolveTextScale(font, text);
        float width = font.width(text) * scale;
        float x = right - width;
        float y = bottom - font.lineHeight * scale;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, text, Math.round(x / scale), Math.round(y / scale), color, false);
        guiGraphics.pose().popPose();
    }

    /** 可用宽度 = 武器栏面板宽度（抓不到就退回 168）。 */
    private static float availableTextWidth() {
        if (RVP_WeaponPanelMetrics.hasExtent()) {
            return RVP_WeaponPanelMetrics.panelWidth();
        }
        return FALLBACK_PANEL_WIDTH;
    }

    private static float resolveTextScale(Font font, String text) {
        float scale = TARGET_TEXT_SCALE;
        float available = availableTextWidth();
        float natural = font.width(text);
        if (natural > 0 && natural * scale > available) {
            scale = Math.max(MIN_TEXT_SCALE, available / natural);
        }
        return scale;
    }

    /** 过热配色沿用本体弹药栏的告警色阶（白 → 橙 → 红）。 */
    private static int heatColor(float ratio) {
        if (ratio >= 1.0f) {
            return RED;
        }
        if (ratio >= 0.75f) {
            return ORANGE;
        }
        return WHITE;
    }
}
