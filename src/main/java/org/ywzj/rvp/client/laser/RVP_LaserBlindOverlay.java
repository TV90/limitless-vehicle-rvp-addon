package org.ywzj.rvp.client.laser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientLaserBlindState;

/**
 * 激光致盲白色闪光滤镜（客户端，2026-09-17）：被 rvp:laser 致盲后全屏白色覆盖，
 * 前段全亮、尾段线性渐隐；期间再次被致盲会刷新时长（重新全亮）。
 * 登出/断线时由 {@code RVP_ClientEvents.LoggingOut} 清理状态。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_LaserBlindOverlay {

    /** 渐隐段占总时长的比例：最后 30% 线性渐隐，其余全亮。 */
    private static final float FADE_RATIO = 0.3f;
    /** 全亮段的不透明度（留 8% 透出画面，避免完全黑屏式致盲引发晕眩）。 */
    private static final int MAX_ALPHA = 235;

    private RVP_LaserBlindOverlay() {
    }

    @SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        // 登出/断线清理致盲状态，防止残留到下一次进入世界
        RVP_ClientLaserBlindState.clear();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        long remaining = RVP_ClientLaserBlindState.remainingMs();
        long total = RVP_ClientLaserBlindState.totalMs();
        if (remaining <= 0L || total <= 0L) {
            return;
        }
        float remainRatio = Math.min(1.0f, (float) remaining / (float) total);
        int alpha = remainRatio >= FADE_RATIO
                ? MAX_ALPHA
                : (int) (MAX_ALPHA * (remainRatio / FADE_RATIO));
        if (alpha <= 0) {
            return;
        }
        GuiGraphics guiGraphics = event.getGuiGraphics();
        guiGraphics.fill(RenderType.gui(), 0, 0,
                guiGraphics.guiWidth(), guiGraphics.guiHeight(),
                (alpha << 24) | 0x00FFFFFF);
    }
}
