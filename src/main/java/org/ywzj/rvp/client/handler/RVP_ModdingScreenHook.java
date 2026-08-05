package org.ywzj.rvp.client.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.screen.RVP_ModdingVariantScreen;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.mixin.accessor.VehicleModdingToolScreenAccessor;
import org.ywzj.vehicle.client.screen.VehicleModdingToolScreen;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ModdingScreenHook {

    private RVP_ModdingScreenHook() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof VehicleModdingToolScreen screen)) {
            return;
        }
        var vehicle = ((VehicleModdingToolScreenAccessor) screen).rvp$getVehicle();
        if (RVP_VehicleExtendedConfigManager.INSTANCE.getModdingOnlyEntries(vehicle).isEmpty()) {
            return;
        }
        int x = 182;
        int y = 34;
        event.addListener(Button.builder(Component.literal("RVP Variants"), button ->
                        Minecraft.getInstance().setScreen(new RVP_ModdingVariantScreen(vehicle, screen)))
                .bounds(x, y, 110, 20)
                .build());
    }
}
