package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ClientEcmVictimState;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;

/**
 * 主动ECM 被干扰方 RWR 表盘伪造锁定 blip 覆盖层（纯公共 API，不改本体）。
 *
 * <p>表盘位置与缩放与 {@link RVP_RadarOverlay} 完全一致：读 {@code UIPresetManager.getRwr(presetName)}
 * 计算 {@code rwrCenterX/Y}，因此 RVP 自定义 UI 把 RWR 移到任意位置时本覆盖层同步跟随。</p>
 *
 * <p>样式对齐 RVP/本体的 RWR 锁定画法：从表盘中心向目标方向画绿色锁定线
 * （{@link RenderHelper#drawLine}，色 {@code 0xDD00FF00}），并在半径 ~28px 处以 0.6 缩放居中画机型标签。
 * 伪目标无真实实体、无真实方位，故角度与半径均按机型 hash 稳定伪随机分布（8~26px），
 * 而不是全部贴在最外缘。</p>
 */
public class RVP_EcmFakeRwrOverlay implements IGuiOverlay {

    /** 与本体 {@code Color.GREEN} 一致（半透明绿）。 */
    private static final int COLOR_GREEN = 0xDD00FF00;
    /** 标签偏移半径（px，与 RVP/本体 scale*28 一致）。 */
    private static final int LABEL_RADIUS = 28;
    /** 最小 blip 半径（px）。 */
    private static final int MIN_RADIUS = 8;
    /** 半径随机区间长度：8 + hash%19 → [8, 26]。 */
    private static final int RADIUS_RANGE = 19;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
                       int screenWidth, int screenHeight) {
        if (!RVP_ClientEcmVictimState.isLocked()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || LocalVehiclePlayer.instance == null) {
            return;
        }
        WeaponUnit wu = LocalVehiclePlayer.instance.getWeaponUnit();
        if (wu == null) {
            return;
        }
        AbstractVehicle vehicle = wu.getVehicle();
        if (vehicle == null) {
            return;
        }
        // 与 RVP_RadarOverlay 相同的 RWR 位置解析，确保随 UI 预设同步移动
        String presetName = vehicle.getVehicleId() != null ? VehicleUIPresetCache.get(vehicle.getVehicleId()) : null;
        UIPosition rwrPos = UIPresetManager.getRwr(presetName);
        int rwrCenterX = rwrPos != null ? rwrPos.computeX(screenWidth) : screenWidth / 2 + 128;
        int rwrCenterY = rwrPos != null ? rwrPos.computeY(screenHeight) : screenHeight - 180;

        List<String> types = RVP_ClientEcmVictimState.getTypes();
        int n = Math.min(types.size(), 10);
        if (n <= 0) {
            return;
        }
        renderLockBlips(guiGraphics, mc.font, rwrCenterX, rwrCenterY, types, n);
    }

    private static void renderLockBlips(GuiGraphics gg, Font font, int cx, int cy, List<String> types, int n) {
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        double base = Math.PI / 2.0;
        for (int i = 0; i < n; i++) {
            String type = types.get(i);
            int h = Math.abs(type.hashCode());
            // 稳定伪随机：角度均匀分布 + hash 微偏；半径 8~26px 随机分布（避免全贴外缘）
            double angle = base + (i / (double) n) * Math.PI * 2.0 + ((h % 60) / 60.0) * 0.5 - 0.25;
            double radius = MIN_RADIUS + (h % RADIUS_RANGE);
            double sx = Math.sin(angle);
            double cz = Math.cos(angle);
            // 本体同款：从中心画绿色锁定线到 blip
            RenderHelper.drawLine(pose, new Vec3(sx * radius, 0.0D, cz * radius),
                    0.8f, COLOR_GREEN, 3, 1);
            // 本体同款：0.6 缩放居中画机型标签（略在 blip 外侧）
            pose.pushPose();
            pose.translate(sx * (radius + 3), cz * (radius + 3), 0);
            pose.scale(0.6f, 0.6f, 1.0f);
            gg.drawCenteredString(font, Component.literal(type), 0, 0, COLOR_GREEN);
            pose.popPose();
        }
        pose.popPose();
    }
}