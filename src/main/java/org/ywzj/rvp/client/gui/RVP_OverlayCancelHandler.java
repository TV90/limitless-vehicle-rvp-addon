package org.ywzj.rvp.client.gui;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 原版 overlay cancel handler。
 * {@code RenderGuiOverlayEvent.Pre} 在 Forge 事件总线上触发，
 * 必须用 {@link MinecraftForge#EVENT_BUS} 注册。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_OverlayCancelHandler {

    private static final ResourceLocation VEHICLE_RADAR = new ResourceLocation("ywzj_vehicle", "vehicle_radar");
    private static final ResourceLocation VEHICLE_SCOPE = new ResourceLocation("ywzj_vehicle", "vehicle_scope");

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
        ResourceLocation id = event.getOverlay().id();
        if (!id.equals(VEHICLE_RADAR) && !id.equals(VEHICLE_SCOPE)) {
            return;
        }
        var vehicle = LocalVehiclePlayer.instance.getVehicle();
        if (vehicle == null || vehicle.getVehicleId() == null) {
            if (id.equals(VEHICLE_RADAR)) {
                event.setCanceled(true);
            }
            return;
        }
        if (id.equals(VEHICLE_RADAR)) {
            event.setCanceled(true);
            return;
        }
        String preset = VehicleUIPresetCache.get(vehicle.getVehicleId());
        if (preset != null && !preset.isEmpty()) {
            event.setCanceled(true);
        }
    }
}
