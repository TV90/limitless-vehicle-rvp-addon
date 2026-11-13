package org.ywzj.rvp.client.gui;

import org.ywzj.rvp.client.performance.RVP_RenderMetric;
import org.ywzj.rvp.client.performance.RVP_RenderStage;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
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
    /** 满屏白闪最大不透明度：与激光致盲滤镜同款（留 8% 透出画面）。 */
    private static final int MAX_ALPHA = 235;

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
        int whiteAlpha = (int) (alpha * MAX_ALPHA);
        if (whiteAlpha <= 0) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        // 调用通用性能范围，默认关闭时不申请显卡查询；所有提前返回和异常均自动封口。
        try (var performance = RVP_RenderPerformance.gui(RVP_RenderStage.DIRCM, gg)) {
            int w = gg.guiWidth();
            int h = gg.guiHeight();
            // 与激光致盲同款：整屏均匀白闪，不再用同心矩形模拟圆形（会显示成方形滤镜）。
            RVP_RenderPerformance.guiQuad(gg, w, h);
            gg.fill(RenderType.gui(), 0, 0, w, h, 0x00FFFFFF | (whiteAlpha << 24));
        }
    }
}