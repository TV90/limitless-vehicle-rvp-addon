package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 炮手 AI 状态行；由 {@link RVP_OverlayRegistry} 注册在 CHAT_PANEL 之下，和本体 HUD 同层。 */
public class RVP_GunnerOverlay {

    public static void render(GuiGraphics gg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return;
        }

        Font font = mc.font;
        int x = 8;
        int y = 8;
        int shown = 0;
        for (Entity passenger : vehicle.getPassengers()) {
            if (!(passenger instanceof GunnerEntity gunner)) {
                continue;
            }
            String profile = gunner.getProfileId();
            int weaponIndex = gunner.getControlledWeaponIndex();
            Entity target = gunner.getTrackedTarget();
            Component targetName = target == null
                    ? Component.translatable("overlay.ywzj_rvp.gunner.no_target")
                    : target.getDisplayName();
            Component profileLabel = getProfileLabel(profile);
            Component line = Component.translatable("overlay.ywzj_rvp.gunner.status", profileLabel, weaponIndex, targetName);
            gg.drawString(font, line, x, y + shown * 10, 0x7CFF7C, true);
            shown++;
            if (shown >= 4) {
                break;
            }
        }
    }

    private static Component getProfileLabel(String profileId) {
        ResourceLocation id = ResourceLocation.tryParse(profileId);
        String path = id == null ? profileId : id.getPath();
        return Component.translatable("tips.ywzj_rvp.gunner_spawner.profile." + path);
    }
}
