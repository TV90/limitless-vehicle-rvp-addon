package org.ywzj.rvp;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.ywzj.rvp.all.RvpEntities;
import org.ywzj.rvp.all.RvpItems;
import org.ywzj.rvp.all.RvpVehicleWeaponTypes;
import org.ywzj.rvp.client.render.GunnerRenderer;
import org.ywzj.rvp.client.render.GPSBombEntityRenderer;
import org.ywzj.rvp.network.RvpNetwork;
import org.ywzj.vehicle.client.render.entity.weapon.MissileEntityRenderer;

@Mod(YwzjRvp.MOD_ID)
public class YwzjRvp {
    public static final String MOD_ID = "ywzj_rvp";

    public static ResourceLocation modLocation(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ResourceLocation resourceLocation(String location) {
        return ResourceLocation.parse(location);
    }

    public YwzjRvp(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        RvpEntities.register(modBus);
        RvpItems.register(modBus);
        RvpVehicleWeaponTypes.register(modBus);
        modBus.addListener(this::onCommonSetup);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> modBus.addListener(this::onClientSetup));
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(RvpNetwork::init);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            EntityRenderers.register(RvpEntities.GPS_BOMB.get(), GPSBombEntityRenderer::new);
            EntityRenderers.register(RvpEntities.ANTI_RADIATION_MISSILE.get(), MissileEntityRenderer::new);
            EntityRenderers.register(RvpEntities.TV_MISSILE.get(), MissileEntityRenderer::new);
            EntityRenderers.register(RvpEntities.GUNNER.get(), GunnerRenderer::new);
        });
    }
}
