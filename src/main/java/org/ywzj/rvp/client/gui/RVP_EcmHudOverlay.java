package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_EcmActiveHudState;
import org.ywzj.rvp.client.state.RVP_EcmHudState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * ECM 被动电子战 HUD：显示各 ECM 通道的干扰/充能状态。
 * 由服务端 {@link org.ywzj.rvp.network.S2CEcmHudSync} 推送数据驱动。
 */
public class RVP_EcmHudOverlay implements IGuiOverlay {

    /** 未配置预设时的默认位置：左缘 8、垂直居中上方 20（GUI 单位，位于 DIRCM 下方）。 */
    private static final int DEFAULT_X = 8;
    private static final int DEFAULT_Y_OFFSET = -20;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        var vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }

        int x = DEFAULT_X;
        int y = Math.max(screenHeight / 2 + Math.round(DEFAULT_Y_OFFSET * ((float) screenHeight / 1080)), 15);

        var font = Minecraft.getInstance().font;

        // 被动ECM：仅在有配置时显示一行
        RVP_EcmHudState.Snapshot state = RVP_EcmHudState.get(vehicle.getId());
        boolean hasPassive = state != null && state.channels() != null && !state.channels().isEmpty();
        if (hasPassive) {
            // 聚合显示：不分骨骼，单行显示整车 ECM 状态（与 DIRCM 多通道不同，ECM 为整车共享状态）
            int totalDecoys = 0;
            int maxCharge = 0;
            for (RVP_EcmHudState.ChannelSnapshot channel : state.channels()) {
                totalDecoys += channel.decoyCount();
                maxCharge = Math.max(maxCharge, channel.chargeRemainTick());
            }
            String line;
            int color;
            if (totalDecoys > 0) {
                line = "ECM防御:反制中 (" + totalDecoys + " 假目标)";
                color = Color.GREEN;
            } else if (maxCharge > 0) {
                int seconds = (maxCharge + 19) / 20;
                line = "ECM防御:充能 " + seconds + "s";
                color = Color.GRAY;
            } else {
                line = "ECM防御:就绪";
                color = Color.GREEN;
            }
            // TODO: 若需用 RVP 自定义 UI 组件绘制（如带背景/图标的 HUD 组件），可在此替换为对应组件的渲染调用
            guiGraphics.drawString(font, line, x, y, color, false);
            // 干扰提示：被动ECM 干扰中时追加提示行
            if (totalDecoys > 0) {
                String tip = "干扰提示: 假目标已生效";
                guiGraphics.drawString(font, tip, x, y + 10, Color.GREEN, false);
            }
        }

        // 主动ECM 状态（与干扰物 HUD 的简要行互补，此处显示详细干扰提示）
        RVP_EcmActiveHudState.Snapshot activeState = RVP_EcmActiveHudState.get(vehicle.getId());
        if (activeState != null) {
            int activeY = hasPassive ? y + 20 : y;
            String activeLine;
            int activeColor;
            if (activeState.isActive()) {
                int seconds = (activeState.activeRemainTick() + 19) / 20;
                activeLine = "ECM干扰: 反制中 " + seconds + "s";
                activeColor = Color.GREEN;
                guiGraphics.drawString(font, activeLine, x, activeY, activeColor, false);
                // 干扰提示：主动干扰进行中
                String tip = "干扰提示: 正在干扰范围内目标";
                guiGraphics.drawString(font, tip, x, activeY + 10, Color.GREEN, false);
            } else if (activeState.isCoolingDown()) {
                int seconds = (activeState.cooldownRemainTick() + 19) / 20;
                activeLine = "ECM干扰: 充能 " + seconds + "s";
                activeColor = Color.GRAY;
                guiGraphics.drawString(font, activeLine, x, activeY, activeColor, false);
            }
        }
    }
}
