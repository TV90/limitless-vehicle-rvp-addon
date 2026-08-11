package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

public class RVP_HeatHudOverlay implements IGuiOverlay {

    private static final int CARD_W = 130;
    private static final int CARD_H = 58;
    private static final int MARGIN_RIGHT = 4;
    private static final int MARGIN_BOTTOM = -12;
    private static final int TAB_H = 14;
    private static final int TAB_EXTRA = 5;

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
        if (!(operatorUnit instanceof WeaponUnit weaponUnit)) {
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

        int tabRowH = TAB_H + TAB_EXTRA;
        int cardRight = screenWidth - MARGIN_RIGHT;
        int cardBottom = screenHeight - MARGIN_BOTTOM - tabRowH;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(cardRight, cardBottom, 0);
        guiGraphics.pose().scale(0.75f, 0.75f, 0.75f);

        int textX = -CARD_W + (CARD_W - textWidth) / 2;
        int textY = -CARD_H - font.lineHeight - 6;

        RenderHelper.fill(guiGraphics, net.minecraft.client.renderer.RenderType.guiOverlay(),
                textX - 4, textY - 2, textX + textWidth + 4, textY + font.lineHeight + 2, 0, BG);
        guiGraphics.hLine(textX - 4, textX + textWidth + 3, textY - 2, BORDER);
        guiGraphics.hLine(textX - 4, textX + textWidth + 3, textY + font.lineHeight + 1, BORDER);
        guiGraphics.vLine(textX - 4, textY - 2, textY + font.lineHeight + 1, BORDER);
        guiGraphics.vLine(textX + textWidth + 3, textY - 2, textY + font.lineHeight + 1, BORDER);
        guiGraphics.drawString(font, text, textX, textY, color, false);

        guiGraphics.pose().popPose();
    }

    private static AbstractVehicleWeapon<?> resolveActiveWeapon(WeaponUnit weaponUnit) {
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
