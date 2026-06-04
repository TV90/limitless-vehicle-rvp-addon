package org.ywzj.rvp.client;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.client.render.GunnerRenderer;

/**
 * Client-only setup; keep out of {@link org.ywzj.rvp.RVP_MOD} so dedicated servers do not load renderer classes.
 */
public final class RVP_ClientBootstrap {

    private RVP_ClientBootstrap() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            RVP_ClientEntityRenderers.register();
            EntityRenderers.register(RVP_Entities.GUNNER.get(), GunnerRenderer::new);
        });
    }
}
