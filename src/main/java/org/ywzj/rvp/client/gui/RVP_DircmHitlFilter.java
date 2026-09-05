package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;

/**
 * DIRCM 对人在回路（HITL）弹干扰时的「中心 → 四周」白闪滤镜（GUI 叠层）。
 *
 * <p>与本体过载滤镜（{@code OverloadHandler}，四周→中心变暗）方向相反：DIRCM 被干扰期间，
 * 白色从屏幕<b>中心</b>迅速向外扩散至满屏，随后随干扰结束（剩余 tick）逐渐消退。
 * 采用 Gui 叠层（用户明确要求，避免 PostChain 盖掉 scope 的滤镜）；与雪花滤镜（
 * {@code RVP_TVMissileOverlay}）互斥——DIRCM 干扰走本滤镜，物理链路阻断走雪花。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_DircmHitlFilter {

    /** 白闪扩散阶段：满屏耗时（tick）。 */
    private static final int SPREAD_TICK = 12;
    /** 白闪消退阶段：消退耗时（tick）。 */
    private static final int FADE_TICK = 30;
    /** 白闪满屏保持时间（tick）——用于「干扰结束后 2 秒逐渐消退」的语义。 */
    private static final int HOLD_TICK = 40;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // RenderGuiOverlayEvent.Post 每帧对每个已注册 overlay 各触发一次（40+ 次），
        // 只锚定每帧必渲染的原生 CHAT_PANEL 层执行一次，其余事件忽略（修复多弹/多实体时帧率腰斩）
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) {
            return;
        }
        if (!RVP_ClientHitlState.isActive() || !RVP_ClientHitlState.isDircmJammed()) {
            return;
        }
        // 干扰剩余 tick：满 tick 时全白，随剩余减少逐渐消退
        int remain = RVP_ClientHitlState.getDircmJamRemainTick();
        if (remain <= 0) {
            return;
        }
        // 干扰总时长（TV=60 / CLOS_TV=120）；进度 = 已干扰比例
        int total = RVP_ClientHitlState.getDircmJamTotalTick();
        if (total <= 0) {
            total = 60;
        }
        float progress = 1.0f - (float) remain / total;
        // 扩散阶段：中心向外扩展；此后保持满屏，消退阶段随剩余减少淡出
        float spread = Mth.clamp(progress / (SPREAD_TICK / (float) total), 0.0f, 1.0f);
        float fade = 1.0f;
        if (remain <= FADE_TICK) {
            fade = (float) remain / FADE_TICK;
        }
        float alpha = spread * fade;

        GuiGraphics gg = event.getGuiGraphics();
        int w = gg.guiWidth();
        int h = gg.guiHeight();
        int cx = w / 2;
        int cy = h / 2;
        // 中心 → 四周：以中心为圆心的多个同心白色叠加，中心优先白
        // 满屏扩散：先画中心小圆，再逐步向外（用矩形模拟径向，简单稳定）
        int maxRadius = (int) Math.ceil(Math.hypot(w, h) / 2.0) + 1;
        int radius = Math.max(1, (int) (maxRadius * spread));
        // 最外层（全屏范围）淡白背景，随扩散覆盖
        int outerAlpha = (int) (alpha * 120);
        if (outerAlpha > 0) {
            // 白色 ARGB：RGB 必须置 0xFFFFFF，否则只有 alpha 通道会显示为半透明黑
            gg.fill(0, 0, w, h, 0xFFFFFF | (outerAlpha << 24));
        }
        // 中心实白区：从中心半径 0 → maxRadius 扩散
        int innerAlpha = (int) (alpha * 255);
        if (innerAlpha > 0 && radius > 0) {
            // 以中心为中心画一个「圆形近似」：逐行填充带水平半宽 = sqrt(r^2 - dy^2)
            int[] steps = {4, 12, 24, 48, 96, 192};
            int lastDy = -1;
            for (int step : steps) {
                int dy = Math.min(radius, step);
                if (dy == lastDy) {
                    continue;
                }
                lastDy = dy;
                int halfWidth = (int) Math.sqrt(Math.max(0, radius * radius - dy * dy));
                if (halfWidth <= 0) {
                    continue;
                }
                gg.fill(cx - halfWidth, cy - dy, cx + halfWidth, cy + dy + 1, 0xFFFFFF | (innerAlpha << 24));
            }
        }
    }
}