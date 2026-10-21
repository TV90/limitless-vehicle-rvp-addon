package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * 过热读数（HEAT xx%）。
 *
 * <p><b>定位</b>：本体自 2026-10 起 HUD 走 {@link VehicleHudLayout}（虚拟 480 高坐标系，
 * 右下角武器栏从 {@code MARGIN=18} 往上堆：弹种行 + 分隔线 + 弹药/装填读出区）。
 * 这里也进同一坐标系，并且用 {@link RVP_WeaponPanelMetrics} 抓到本帧武器栏分隔线的真实 y，
 * 把读数顶在读出区（约 42 高）上方 —— 不再按屏幕像素自己估，避免和新武器栏重叠。</p>
 */
public class RVP_HeatHudOverlay implements IGuiOverlay {

    /** 与本体武器栏一致的右边距（VehicleWeaponOverlay.MARGIN）。 */
    private static final float MARGIN_RIGHT = 18f;

    /** 本体弹药/装填读出区高度（VehicleWeaponOverlay.DETAIL_HEIGHT）。 */
    private static final float READOUT_HEIGHT = 42f;

    /** 读数与读出区之间的间隙。 */
    private static final float GAP_ABOVE_READOUT = 6f;

    /** 抓不到武器栏时（无武器栏/本体改签名）的回退位置，仍高于常见面板。 */
    private static final float FALLBACK_BOTTOM = VehicleHudLayout.HEIGHT - 18f - 96f;

    private static final int BG = 0xB0101010;
    private static final int BORDER = 0xCC3A3A3A;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }

        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }

        PartUnit<?> operatorUnit = vehicle.getOwnOperatorUnit(LocalVehiclePlayer.instance.getPlayer());
        if (!(operatorUnit instanceof org.ywzj.vehicle.vehicle.part.WeaponUnit weaponUnit)) {
            return;
        }

        AbstractVehicleWeapon<?> activeWeapon = resolveActiveWeapon(weaponUnit);
        activeWeapon = RVP_LaserWeapons.unwrap(activeWeapon);
        if (!(activeWeapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }

        if (!rvpWeapon.getFireController().hasHeat()) {
            return;
        }

        float heatRatio = rvpWeapon.getFireController().heatRatio();
        int heatPercent = Math.max(0, Math.round(heatRatio * 100f));
        String text = "HEAT " + heatPercent + "%";
        int color = resolveHeatColor(heatRatio);

        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(text);

        try (VehicleHudLayout layout = VehicleHudLayout.begin(guiGraphics)) {
            float right = layout.width() - MARGIN_RIGHT;
            float textX = right - textWidth;
            float bottom = resolveBottom();
            float textY = bottom - font.lineHeight;

            guiGraphics.fill((int) textX - 3, (int) textY - 2,
                    (int) textX + textWidth + 3, (int) textY + font.lineHeight + 1, BG);
            guiGraphics.fill((int) textX - 3, (int) textY - 2,
                    (int) textX + textWidth + 3, (int) textY - 1, BORDER);
            guiGraphics.fill((int) textX - 3, (int) textY + font.lineHeight,
                    (int) textX + textWidth + 3, (int) textY + font.lineHeight + 1, BORDER);
            guiGraphics.drawString(font, text, (int) textX, (int) textY, color, false);
        }
    }

    /** 读数底边：优先顶在本帧武器栏读出区上方，抓不到才用回退位置。 */
    private static float resolveBottom() {
        if (!RVP_WeaponPanelMetrics.hasSeparator()) {
            return FALLBACK_BOTTOM;
        }
        // separatorY 之上是读出区（高 READOUT_HEIGHT），再往上留 GAP_ABOVE_READOUT
        return RVP_WeaponPanelMetrics.separatorY() - READOUT_HEIGHT - GAP_ABOVE_READOUT;
    }

    private static AbstractVehicleWeapon<?> resolveActiveWeapon(org.ywzj.vehicle.vehicle.part.WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().orElse(null);
        if (weapon != null) {
            return weapon;
        }
        weapon = weaponUnit.getCurrentSecondaryWeapon().orElse(null);
        if (weapon != null) {
            return weapon;
        }
        if (!weaponUnit.independentWeapons.isEmpty()) {
            return weaponUnit.independentWeapons.get(0);
        }
        return null;
    }

    private static int resolveHeatColor(float heatRatio) {
        if (heatRatio >= 1.0f) {
            return Color.RED;
        }
        if (heatRatio >= 0.75f) {
            return 0xFFFFA040;
        }
        if (heatRatio >= 0.5f) {
            return 0xFFFFFF55;
        }
        return 0xFF7CFF7C;
    }
}
