package org.ywzj.rvp;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.all.RVP_WeaponTypes;
import org.ywzj.rvp.client.render.GunnerRenderer;
import org.ywzj.rvp.client.render.RVP_ProjectileEntityRenderer;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.resource.RVP_VehiclePackInstaller;

@Mod(RVP_MOD.MOD_ID)
public class RVP_MOD {
    public static final String MOD_ID = "ywzj_rvp";

    public static ResourceLocation modLocation(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ResourceLocation resourceLocation(String location) {
        return ResourceLocation.parse(location);
    }

    public RVP_MOD(FMLJavaModLoadingContext context) {
        RVP_VehiclePackInstaller.ensureInstalled();
        IEventBus modBus = context.getModEventBus();
        RVP_Entities.register(modBus);
        RVP_Items.register(modBus);
        RVP_WeaponTypes.register(modBus);
        modBus.addListener(this::onCommonSetup);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> modBus.addListener(this::onClientSetup));
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(RVP_Network::init);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            EntityRenderers.register(RVP_Entities.RVP_MISSILE.get(), RVP_ProjectileEntityRenderer::new);
            EntityRenderers.register(RVP_Entities.RVP_ROCKET.get(), RVP_ProjectileEntityRenderer::new);
            EntityRenderers.register(RVP_Entities.RVP_BULLET.get(), RVP_ProjectileEntityRenderer::new);
            EntityRenderers.register(RVP_Entities.RVP_BOMB.get(), RVP_ProjectileEntityRenderer::new);
            EntityRenderers.register(RVP_Entities.RVP_DISPENSED.get(), RVP_ProjectileEntityRenderer::new);
            EntityRenderers.register(RVP_Entities.GUNNER.get(), GunnerRenderer::new);
        });
    }
}
