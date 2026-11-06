package org.ywzj.rvp.client.gui;

import org.ywzj.rvp.client.performance.RVP_RenderMetric;
import org.ywzj.rvp.client.performance.RVP_RenderStage;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.shader.RVP_NvgHandler;

/**
 * 光影包接管渲染管线时的夜视仪降级表现（2026-10-31）。
 *
 * <p>Iris/Oculus 启用光影后，原版 {@code PostChain} 的后处理不再可靠，
 * {@link RVP_NvgHandler} 会主动跳过。此时退化为 GUI 叠层：全屏 P43 绿罩 + 四边暗角。
 * 它做不到像增强和眩光（那需要读世界画面），只在观感上保留"在用夜视仪"的信息。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_NvgGuiFallback {

    /** 全屏绿罩（ARGB）：P43 荧光绿，低 alpha 保证 HUD 仍可读。 */
    private static final int TINT_COLOR = 0x30243F16;
    /** 暗角起始 alpha 与层数：逐层加深模拟管壁衰减。 */
    private static final int VIGNETTE_LAYERS = 10;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // 与 DIRCM 滤镜同样的锚点策略：每帧只在 CHAT_PANEL 层执行一次
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        // 只有在 PostChain 真失败时才走这里；能跑 PostChain 的情况下不再叠一层绿罩
        if (!RVP_NvgHandler.isFallbackActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        // 调用通用性能范围，默认关闭时不申请显卡查询；所有提前返回和异常均自动封口。
        try (var performance = RVP_RenderPerformance.gui(RVP_RenderStage.NIGHT_VISION_FALLBACK, graphics)) {
            int width = graphics.guiWidth();
            int height = graphics.guiHeight();
            RVP_RenderPerformance.guiQuad(graphics, width, height);
            graphics.fill(0, 0, width, height, TINT_COLOR);

            // 四边暗角：越靠边越黑，中心保持通透
            int stepX = Math.max(1, width / 40);
            int stepY = Math.max(1, height / 40);
            for (int i = 0; i < VIGNETTE_LAYERS; i++) {
                int alpha = 0x10 - i;
                if (alpha <= 0) {
                    break;
                }
                int argb = alpha << 24;
                int insetX = i * stepX;
                int insetY = i * stepY;
                int bandX = Math.max(1, stepX);
                int bandY = Math.max(1, stepY);
                // 上下两条横带
                RVP_RenderPerformance.guiQuad(graphics, width, bandY);
                graphics.fill(0, insetY, width, insetY + bandY, argb);
                RVP_RenderPerformance.guiQuad(graphics, width, bandY);
                graphics.fill(0, height - insetY - bandY, width, height - insetY, argb);
                // 左右两条竖带
                RVP_RenderPerformance.guiQuad(graphics, bandX, height);
                graphics.fill(insetX, 0, insetX + bandX, height, argb);
                RVP_RenderPerformance.guiQuad(graphics, bandX, height);
                graphics.fill(width - insetX - bandX, 0, width - insetX, height, argb);
            }
        }
    }
}
