package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

public class RVP_ChargeBarOverlay implements IGuiOverlay {

    private static final int BG = (0x90 << 24) | 0x000000;
    private static final int FG = (0xDD << 24) | 0x66D9FF;
    private static final int BORDER = (0xAA << 24) | 0xFFFFFF;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        AbstractVehicleWeapon<?> rawWeapon = weaponUnit.getCurrentWeapon().orElse(null);
        AbstractVehicleWeapon<?> weapon = RVP_LaserWeapons.unwrap(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvp)) {
            return;
        }
        int cap = rvp.getData().getFireData().getChargeTime();
        if (cap <= 0) {
            return;
        }
        RVP_EnumFireMode mode = rvp.getData().getFireData().getFireMode();
        if (mode != RVP_EnumFireMode.CHARGE && mode != RVP_EnumFireMode.MINIGUN && mode != RVP_EnumFireMode.RAILGUN) {
            return;
        }
        int tick = rvp.getChargeTickValue();
        if (tick <= 0) {
            return;
        }
        float ratio = Math.min(tick / (float) cap, 1.0f);

        double x = VehicleAimAtOverlay.getScreenAimX();
        double y = VehicleAimAtOverlay.getScreenAimY();

        int barW = 44;
        int barH = 4;
        int ix = (int) Math.round(x) - barW / 2;
        int iy = (int) Math.round(y) + 72;
        int fillW = Math.max(0, Math.min(barW, Math.round(barW * ratio)));

        guiGraphics.fill(ix - 1, iy - 1, ix + barW + 1, iy + barH + 1, BORDER);
        guiGraphics.fill(ix, iy, ix + barW, iy + barH, BG);
        guiGraphics.fill(ix, iy, ix + fillW, iy + barH, FG);

        int pct = Math.round(ratio * 100f);
        guiGraphics.drawString(Minecraft.getInstance().font, pct + "%", ix + barW / 2 - 8, iy + barH + 6, BORDER, false);
    }
}
