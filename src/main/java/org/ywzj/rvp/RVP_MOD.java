package org.ywzj.rvp;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.all.RVP_WeaponTypes;
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
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                modBus.addListener(org.ywzj.rvp.client.RVP_ClientBootstrap::onClientSetup));
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(RVP_Network::init);
    }
}
