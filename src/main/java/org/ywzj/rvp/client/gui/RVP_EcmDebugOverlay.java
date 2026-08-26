package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_DebugOverlayState;
import org.ywzj.rvp.client.state.RVP_ClientEcmDebugState;
import org.ywzj.rvp.network.S2CEcmDebug;

/**
 * 主动ECM 调试覆盖层（F10 开启）。显示玩家自身载具 ECM 状态、本人被干扰的导弹数，
 * 以及附近正在放主动ECM 的载具清单。数据来自 {@link S2CEcmDebug}（单客户端走回环网络）。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_EcmDebugOverlay {

    private static final int COLOR_TITLE = 0xFFFFA040;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_WARN = 0xFFFF5050;

    private RVP_EcmDebugOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!RVP_DebugOverlayState.isEnabled()) {
            return;
        }
        if (!RVP_ClientEcmDebugState.hasData()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        S2CEcmDebug d = RVP_ClientEcmDebugState.get();
        if (d == null) {
            return;
        }
        render(event.getGuiGraphics(), mc.font, d);
    }

    private static void render(GuiGraphics gg, Font font, S2CEcmDebug d) {
        int x = 8;
        int y = 8;
        int lineH = font.lineHeight + 2;

        gg.drawString(font, Component.literal("[主动ECM 调试]"), x, y, COLOR_TITLE, true);
        y += lineH;

        String own = (d.ownEquipped() ? "装备=是" : "装备=否")
                + " | 释放=" + (d.ownActive() ? "是" : "否")
                + " | 剩余=" + (d.ownActiveRemain() / 20) + "s"
                + " | 冷却=" + (d.ownCooldownRemain() / 20) + "s";
        gg.drawString(font, Component.literal(own), x, y, d.ownEquipped() ? COLOR_TEXT : COLOR_DIM, true);
        y += lineH;

        gg.drawString(font, Component.literal("本人被干扰导弹=" + d.myMissilesJammed()),
                x, y, d.myMissilesJammed() > 0 ? COLOR_WARN : COLOR_TEXT, true);
        y += lineH;

        gg.drawString(font, Component.literal("附近放ECM载具=" + d.entries().size()), x, y, COLOR_TEXT, true);
        y += lineH;

        for (S2CEcmDebug.Entry e : d.entries()) {
            String line = "  #" + e.vehicleId
                    + " dist=" + e.dist + "m"
                    + " ammoR=" + e.ammoRadius
                    + " vehR=" + e.vehicleRadius
                    + " 剩余=" + (e.activeRemain / 20) + "s";
            gg.drawString(font, Component.literal(line), x, y, COLOR_DIM, true);
            y += lineH;
        }
    }
}
