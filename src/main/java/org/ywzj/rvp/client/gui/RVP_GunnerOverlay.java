package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_GunnerOverlay {

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
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
