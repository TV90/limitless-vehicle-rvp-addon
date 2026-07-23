package org.ywzj.rvp.client;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.client.debug.RVP_SbmProbeDebug;
import org.ywzj.rvp.client.render.GunnerRenderer;
import org.ywzj.vehicle.all.AllEntities;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;

/**
 * Client-only setup; keep out of {@link org.ywzj.rvp.RVP_MOD} so dedicated servers do not load renderer classes.
 */
public final class RVP_ClientBootstrap {

    private RVP_ClientBootstrap() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            RVP_ClientEntityRenderers.register();
            EntityRenderers.register(RVP_Entities.GUNNER.get(), GunnerRenderer::new);
            registerVehicleRenderers();
        });
    }

    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            registerVehicleRenderers();
            RVP_SbmProbeDebug.dumpCurrentVehicleState("load-complete-reregister");
        });
    }

    private static void registerVehicleRenderers() {
        EntityRenderers.register(AllEntities.NONE_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.WHEELED_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.TRACKED_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.ROTARY_WING_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.FIXED_WING_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.ZTZ99A.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.Z10.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.MOTORCYCLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.HIACE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.M1A2.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.CSSA5.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.AH64D.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.LAV_AD.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.BGM_71_TOW.get(), VehicleRender::new);
    }
}
